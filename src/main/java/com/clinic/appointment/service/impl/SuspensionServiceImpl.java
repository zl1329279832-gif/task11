package com.clinic.appointment.service.impl;

import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.model.dto.SuspensionRequest;
import com.clinic.appointment.model.entity.*;
import com.clinic.appointment.model.enums.AuditAction;
import com.clinic.appointment.model.enums.ScheduleStatus;
import com.clinic.appointment.model.enums.WaitlistStatus;
import com.clinic.appointment.service.ScheduleService;
import com.clinic.appointment.service.SuspensionService;
import com.clinic.appointment.util.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SuspensionServiceImpl implements SuspensionService {

    private final SuspensionMapper suspensionMapper;
    private final ScheduleMapper scheduleMapper;
    private final ScheduleService scheduleService;
    private final AppointmentMapper appointmentMapper;
    private final SlotMapper slotMapper;
    private final WaitlistMapper waitlistMapper;
    private final DoctorMapper doctorMapper;
    private final AuditUtil auditUtil;

    @Override
    @Transactional
    public Suspension suspend(SuspensionRequest request) {
        // Find affected schedules for the doctor on the given date
        List<Schedule> schedules = findAffectedSchedules(request);
        if (schedules.isEmpty()) {
            throw new BusinessException("No active schedules found for doctor "
                    + request.getDoctorId() + " on " + request.getSuspendDate());
        }

        String action = request.getAction();
        Long lastScheduleId = null;

        for (Schedule schedule : schedules) {
            lastScheduleId = schedule.getId();

            // Mark schedule as SUSPENDED
            scheduleService.suspend(schedule.getId());

            // Get all active appointments for this schedule
            List<Appointment> activeAppointments = appointmentMapper.selectActiveByScheduleId(schedule.getId());

            if ("CANCEL".equalsIgnoreCase(action)) {
                handleCancelAction(schedule, activeAppointments, request.getReason());
            } else if ("MIGRATE".equalsIgnoreCase(action)) {
                handleMigrateAction(schedule, activeAppointments, request);
            } else {
                throw new BusinessException("Unknown suspension action: " + action);
            }

            // Cancel all WAITING waitlist entries for this schedule
            cancelWaitingEntries(schedule.getId());
        }

        // Create and persist the Suspension record
        Suspension suspension = Suspension.builder()
                .doctorId(request.getDoctorId())
                .scheduleId(lastScheduleId)
                .suspendDate(request.getSuspendDate())
                .period(request.getPeriod())
                .reason(request.getReason())
                .actionTaken(action.toUpperCase())
                .targetDoctorId(request.getTargetDoctorId())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        suspensionMapper.insert(suspension);

        AuditAction auditAction = "MIGRATE".equalsIgnoreCase(action)
                ? AuditAction.MIGRATE : AuditAction.SUSPEND;

        auditUtil.log("SUSPENSION", suspension.getId(), auditAction,
                "SYSTEM",
                "Doctor " + request.getDoctorId() + " suspended on " + request.getSuspendDate()
                        + ". Action: " + action + ". Reason: " + request.getReason());

        log.info("Suspension {} created for doctor {} on {} with action {}",
                suspension.getId(), request.getDoctorId(), request.getSuspendDate(), action);

        return suspension;
    }

    /**
     * Find schedules affected by the suspension request.
     * If period is specified, only that period; otherwise all periods for the date.
     */
    private List<Schedule> findAffectedSchedules(SuspensionRequest request) {
        if (request.getPeriod() != null && !request.getPeriod().isEmpty()) {
            // Specific period requested
            Schedule schedule = scheduleMapper.selectActiveByDoctorAndDate(
                    request.getDoctorId(), request.getSuspendDate(), request.getPeriod());
            List<Schedule> result = new ArrayList<>();
            if (schedule != null) {
                result.add(schedule);
            }
            return result;
        } else {
            // All periods: use date range with same start/end to get all schedules for that day
            List<Schedule> allSchedules = scheduleMapper.selectByDoctorAndDateRange(
                    request.getDoctorId(), request.getSuspendDate(), request.getSuspendDate());
            // Filter to only ACTIVE schedules
            return allSchedules.stream()
                    .filter(s -> ScheduleStatus.ACTIVE.name().equals(s.getStatus()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * CANCEL action: cancel all active appointments and release their slots.
     */
    private void handleCancelAction(Schedule schedule, List<Appointment> activeAppointments, String reason) {
        if (activeAppointments.isEmpty()) {
            return;
        }

        String cancelReason = "Doctor suspension: " + reason;

        // Batch update appointment statuses to CANCELLED
        List<Long> appointmentIds = activeAppointments.stream()
                .map(Appointment::getId)
                .collect(Collectors.toList());
        appointmentMapper.batchUpdateStatus(appointmentIds, "CANCELLED", cancelReason);

        // Release each slot back to AVAILABLE
        for (Appointment appt : activeAppointments) {
            Slot slot = slotMapper.selectById(appt.getSlotId());
            if (slot != null) {
                slotMapper.updateStatus(slot.getId(), "AVAILABLE", slot.getStatus(), slot.getVersion());
            }
        }

        log.info("Cancelled {} appointments for schedule {} due to suspension",
                activeAppointments.size(), schedule.getId());
    }

    /**
     * MIGRATE action: migrate appointments to target doctor's schedule.
     * Handles partial migration gracefully -- migrates what we can, cancels the rest.
     */
    private void handleMigrateAction(Schedule sourceSchedule, List<Appointment> activeAppointments,
                                     SuspensionRequest request) {
        if (request.getTargetDoctorId() == null) {
            throw new BusinessException("Target doctor ID is required for MIGRATE action");
        }

        // Find target doctor's schedule for the same date and period
        Schedule targetSchedule = scheduleMapper.selectActiveByDoctorAndDate(
                request.getTargetDoctorId(), request.getSuspendDate(), sourceSchedule.getPeriod());
        if (targetSchedule == null) {
            throw new BusinessException("Target doctor has no schedule on this date");
        }

        // Get available slots in target schedule
        List<Slot> availableSlots = slotMapper.selectAvailableByScheduleId(targetSchedule.getId());

        Doctor targetDoctor = doctorMapper.selectById(request.getTargetDoctorId());

        int migratedCount = 0;
        List<Appointment> remainingAppointments = new ArrayList<>();

        for (Appointment appt : activeAppointments) {
            if (migratedCount < availableSlots.size()) {
                // Migrate this appointment to the target schedule
                Slot targetSlot = availableSlots.get(migratedCount);

                // CAS-lock the target slot: AVAILABLE -> BOOKED
                int updated = slotMapper.updateStatus(
                        targetSlot.getId(), "BOOKED", "AVAILABLE", targetSlot.getVersion());
                if (updated == 0) {
                    // CAS failed, treat as remaining
                    remainingAppointments.add(appt);
                    continue;
                }

                // Update old appointment to RESCHEDULED
                appointmentMapper.updateStatus(appt.getId(), "RESCHEDULED");

                // Release old slot
                Slot oldSlot = slotMapper.selectById(appt.getSlotId());
                if (oldSlot != null) {
                    slotMapper.updateStatus(oldSlot.getId(), "AVAILABLE", oldSlot.getStatus(), oldSlot.getVersion());
                }

                // Create new appointment pointing to target doctor/schedule/slot
                Appointment newAppt = Appointment.builder()
                        .patientId(appt.getPatientId())
                        .patientName(appt.getPatientName())
                        .slotId(targetSlot.getId())
                        .scheduleId(targetSchedule.getId())
                        .doctorId(request.getTargetDoctorId())
                        .departmentId(targetDoctor != null ? targetDoctor.getDepartmentId() : null)
                        .scheduleDate(request.getSuspendDate())
                        .period(sourceSchedule.getPeriod())
                        .seqNum(targetSlot.getSeqNum())
                        .status("BOOKED")
                        .source("MIGRATION")
                        .originalAppointmentId(appt.getId())
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();

                appointmentMapper.insert(newAppt);
                scheduleMapper.incrementBookedCount(targetSchedule.getId());

                migratedCount++;

                log.debug("Migrated appointment {} to new appointment {} on target schedule {}",
                        appt.getId(), newAppt.getId(), targetSchedule.getId());
            } else {
                remainingAppointments.add(appt);
            }
        }

        // Decrement booked count for source schedule
        for (int i = 0; i < activeAppointments.size(); i++) {
            scheduleMapper.decrementBookedCount(sourceSchedule.getId());
        }

        // Cancel remaining appointments that could not be migrated
        if (!remainingAppointments.isEmpty()) {
            String cancelReason = "Doctor suspension: not enough slots for migration";
            List<Long> remainingIds = remainingAppointments.stream()
                    .map(Appointment::getId)
                    .collect(Collectors.toList());
            appointmentMapper.batchUpdateStatus(remainingIds, "CANCELLED", cancelReason);

            // Release slots for cancelled appointments
            for (Appointment appt : remainingAppointments) {
                Slot slot = slotMapper.selectById(appt.getSlotId());
                if (slot != null) {
                    slotMapper.updateStatus(slot.getId(), "AVAILABLE", slot.getStatus(), slot.getVersion());
                }
            }

            log.warn("Could not migrate {} appointments for schedule {} due to insufficient target slots",
                    remainingAppointments.size(), sourceSchedule.getId());
        }

        log.info("Migrated {}/{} appointments from schedule {} to target doctor {}",
                migratedCount, activeAppointments.size(), sourceSchedule.getId(), request.getTargetDoctorId());
    }

    /**
     * Cancel all WAITING waitlist entries for a schedule.
     */
    private void cancelWaitingEntries(Long scheduleId) {
        List<Waitlist> waitingEntries = waitlistMapper.selectByScheduleIdAndStatus(
                scheduleId, WaitlistStatus.WAITING.name());

        for (Waitlist entry : waitingEntries) {
            waitlistMapper.updateStatus(entry.getId(), WaitlistStatus.CANCELLED.name());
        }

        if (!waitingEntries.isEmpty()) {
            log.info("Cancelled {} waiting waitlist entries for schedule {}", waitingEntries.size(), scheduleId);
        }
    }
}
