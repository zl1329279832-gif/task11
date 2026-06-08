package com.clinic.appointment.service.impl;

import com.clinic.appointment.config.AppointmentConfig;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.entity.Waitlist;
import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.model.enums.AuditAction;
import com.clinic.appointment.model.enums.WaitlistStatus;
import com.clinic.appointment.service.WaitlistService;
import com.clinic.appointment.util.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistServiceImpl implements WaitlistService {

    private final WaitlistMapper waitlistMapper;
    private final SlotMapper slotMapper;
    private final AppointmentMapper appointmentMapper;
    private final ScheduleMapper scheduleMapper;
    private final DoctorMapper doctorMapper;
    private final AuditUtil auditUtil;
    private final AppointmentConfig config;

    @Override
    @Transactional
    public Waitlist joinWaitlist(String patientId, String patientName, Long scheduleId) {
        // Check waitlist capacity
        int count = waitlistMapper.countByScheduleId(scheduleId);
        if (count >= config.getMaxWaitlistSize()) {
            throw new BusinessException("Waitlist is full");
        }

        // Determine next queue position
        Integer maxPos = waitlistMapper.selectMaxPosition(scheduleId);
        int position = (maxPos == null) ? 1 : maxPos + 1;

        // Get schedule to populate doctor/department/date/period
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw new BusinessException("Schedule not found: " + scheduleId);
        }

        Doctor doctor = doctorMapper.selectById(schedule.getDoctorId());
        if (doctor == null) {
            throw new BusinessException("Doctor not found: " + schedule.getDoctorId());
        }

        Waitlist waitlist = Waitlist.builder()
                .patientId(patientId)
                .patientName(patientName)
                .scheduleId(scheduleId)
                .doctorId(schedule.getDoctorId())
                .departmentId(doctor.getDepartmentId())
                .scheduleDate(schedule.getScheduleDate())
                .period(schedule.getPeriod())
                .queuePosition(position)
                .status(WaitlistStatus.WAITING.name())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        waitlistMapper.insert(waitlist);

        auditUtil.log("WAITLIST", waitlist.getId(), AuditAction.WAITLIST_JOIN,
                patientId, "Joined waitlist for schedule " + scheduleId + " at position " + position);

        log.info("Patient {} joined waitlist for schedule {} at position {}", patientId, scheduleId, position);
        return waitlist;
    }

    @Override
    @Transactional
    public void cancelWaitlist(Long waitlistId) {
        Waitlist waitlist = waitlistMapper.selectById(waitlistId);
        if (waitlist == null) {
            throw new BusinessException("Waitlist entry not found: " + waitlistId);
        }

        String status = waitlist.getStatus();
        if (!WaitlistStatus.WAITING.name().equals(status) && !WaitlistStatus.OFFERED.name().equals(status)) {
            throw new BusinessException("Cannot cancel waitlist entry with status: " + status);
        }

        waitlistMapper.updateStatus(waitlistId, WaitlistStatus.CANCELLED.name());

        auditUtil.log("WAITLIST", waitlistId, AuditAction.WAITLIST_CANCEL,
                waitlist.getPatientId(), "Cancelled waitlist entry");

        log.info("Waitlist entry {} cancelled for patient {}", waitlistId, waitlist.getPatientId());
    }

    @Override
    @Transactional
    public boolean tryFillFromWaitlist(Long scheduleId) {
        // Get first waiting entry in the queue
        Waitlist waitlist = waitlistMapper.selectFirstWaiting(scheduleId);
        if (waitlist == null) {
            log.debug("No waiting entries for schedule {}", scheduleId);
            return false;
        }

        // Find an available slot
        List<Slot> availableSlots = slotMapper.selectAvailableByScheduleId(scheduleId);
        if (availableSlots.isEmpty()) {
            log.debug("No available slots for schedule {}", scheduleId);
            return false;
        }

        Slot slot = availableSlots.get(0);

        // CAS-lock the slot: AVAILABLE -> BOOKED
        int updated = slotMapper.updateStatus(slot.getId(), "BOOKED", "AVAILABLE", slot.getVersion());
        if (updated == 0) {
            log.warn("CAS failed when trying to fill waitlist for schedule {}, slot {}", scheduleId, slot.getId());
            return false;
        }

        // Get schedule and doctor for appointment details
        Schedule schedule = scheduleMapper.selectById(scheduleId);
        Doctor doctor = doctorMapper.selectById(slot.getDoctorId());

        // Create appointment from waitlist entry
        Appointment appointment = Appointment.builder()
                .patientId(waitlist.getPatientId())
                .patientName(waitlist.getPatientName())
                .slotId(slot.getId())
                .scheduleId(scheduleId)
                .doctorId(slot.getDoctorId())
                .departmentId(doctor != null ? doctor.getDepartmentId() : null)
                .scheduleDate(slot.getScheduleDate())
                .period(slot.getPeriod())
                .seqNum(slot.getSeqNum())
                .status("BOOKED")
                .source("WAITLIST")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        appointmentMapper.insert(appointment);

        // Increment schedule booked count
        scheduleMapper.incrementBookedCount(schedule.getId());

        // Update waitlist status to CONVERTED
        waitlistMapper.updateStatus(waitlist.getId(), WaitlistStatus.CONVERTED.name());

        auditUtil.log("WAITLIST", waitlist.getId(), AuditAction.WAITLIST_CONVERT,
                waitlist.getPatientId(),
                "Converted waitlist to appointment " + appointment.getId() + " for slot " + slot.getId());

        log.info("Waitlist entry {} converted to appointment {} for patient {}",
                waitlist.getId(), appointment.getId(), waitlist.getPatientId());
        return true;
    }

    @Override
    public List<Waitlist> getByPatientId(String patientId) {
        return waitlistMapper.selectByPatientId(patientId);
    }

    @Override
    public List<Waitlist> getByScheduleIdAndStatus(Long scheduleId, String status) {
        return waitlistMapper.selectByScheduleIdAndStatus(scheduleId, status);
    }
}
