package com.clinic.appointment.service.impl;

import com.clinic.appointment.config.AppointmentConfig;
import com.clinic.appointment.exception.BusinessException;
import com.clinic.appointment.exception.ConcurrentBookingException;
import com.clinic.appointment.exception.SlotUnavailableException;
import com.clinic.appointment.mapper.*;
import com.clinic.appointment.model.dto.BookingRequest;
import com.clinic.appointment.model.dto.CancelRequest;
import com.clinic.appointment.model.dto.RescheduleRequest;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.enums.AppointmentStatus;
import com.clinic.appointment.model.enums.AuditAction;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.WaitlistService;
import com.clinic.appointment.util.AuditUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppointmentServiceImpl implements AppointmentService {

    private final AppointmentMapper appointmentMapper;
    private final SlotMapper slotMapper;
    private final ScheduleMapper scheduleMapper;
    private final DoctorMapper doctorMapper;
    private final WaitlistService waitlistService;
    private final AuditUtil auditUtil;
    private final RedissonClient redissonClient;
    private final AppointmentConfig config;

    @Override
    @Transactional
    public Appointment book(BookingRequest request) {
        String lockKey = "lock:slot:" + request.getSlotId();
        RLock lock = redissonClient.getLock(lockKey);

        boolean acquired;
        try {
            acquired = lock.tryLock(config.getLockWaitSeconds(), 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConcurrentBookingException();
        }

        if (!acquired) {
            throw new ConcurrentBookingException();
        }

        try {
            // Get and validate slot
            Slot slot = slotMapper.selectById(request.getSlotId());
            if (slot == null) {
                throw new BusinessException("Slot not found: " + request.getSlotId());
            }
            if (!"AVAILABLE".equals(slot.getStatus())) {
                throw new SlotUnavailableException(request.getSlotId());
            }

            // CAS update: AVAILABLE -> BOOKED
            int updated = slotMapper.updateStatus(slot.getId(), "BOOKED", "AVAILABLE", slot.getVersion());
            if (updated == 0) {
                throw new ConcurrentBookingException();
            }

            // Get schedule and doctor for appointment details
            Schedule schedule = scheduleMapper.selectById(slot.getScheduleId());
            Doctor doctor = doctorMapper.selectById(slot.getDoctorId());

            // Build and insert appointment
            Appointment appointment = Appointment.builder()
                    .patientId(request.getPatientId())
                    .patientName(request.getPatientName())
                    .slotId(slot.getId())
                    .scheduleId(slot.getScheduleId())
                    .doctorId(slot.getDoctorId())
                    .departmentId(doctor != null ? doctor.getDepartmentId() : null)
                    .scheduleDate(slot.getScheduleDate())
                    .period(slot.getPeriod())
                    .seqNum(slot.getSeqNum())
                    .status(AppointmentStatus.BOOKED.name())
                    .source("NORMAL")
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            appointmentMapper.insert(appointment);

            // Increment booked count on schedule
            scheduleMapper.incrementBookedCount(schedule.getId());

            auditUtil.log("APPOINTMENT", appointment.getId(), AuditAction.BOOK,
                    request.getPatientId(), "Booked slot " + slot.getId());

            log.info("Appointment {} booked for patient {} on slot {}",
                    appointment.getId(), request.getPatientId(), slot.getId());
            return appointment;

        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    @Transactional
    public void cancel(CancelRequest request) {
        Appointment appointment = appointmentMapper.selectById(request.getAppointmentId());
        if (appointment == null) {
            throw new BusinessException("Appointment not found: " + request.getAppointmentId());
        }

        AppointmentStatus status = AppointmentStatus.valueOf(appointment.getStatus());
        if (!status.canCancel()) {
            throw new BusinessException("Cannot cancel appointment with status: " + appointment.getStatus());
        }

        // Update appointment to CANCELLED
        appointment.setStatus(AppointmentStatus.CANCELLED.name());
        appointment.setCancelReason(request.getReason());
        appointment.setCancelledAt(LocalDateTime.now());
        appointment.setUpdatedAt(LocalDateTime.now());
        appointmentMapper.update(appointment);

        // Release slot: BOOKED -> AVAILABLE (CAS)
        Slot slot = slotMapper.selectById(appointment.getSlotId());
        if (slot != null) {
            slotMapper.updateStatus(slot.getId(), "AVAILABLE", "BOOKED", slot.getVersion());
        }

        // Decrement booked count
        scheduleMapper.decrementBookedCount(appointment.getScheduleId());

        auditUtil.log("APPOINTMENT", appointment.getId(), AuditAction.CANCEL,
                appointment.getPatientId(),
                "Cancelled appointment. Reason: " + request.getReason());

        log.info("Appointment {} cancelled for patient {}", appointment.getId(), appointment.getPatientId());

        // IMPORTANT: trigger waitlist backfill after slot is released
        waitlistService.tryFillFromWaitlist(appointment.getScheduleId());
    }

    @Override
    @Transactional
    public Appointment reschedule(RescheduleRequest request) {
        // Get and validate old appointment
        Appointment oldAppointment = appointmentMapper.selectById(request.getAppointmentId());
        if (oldAppointment == null) {
            throw new BusinessException("Appointment not found: " + request.getAppointmentId());
        }

        AppointmentStatus oldStatus = AppointmentStatus.valueOf(oldAppointment.getStatus());
        if (!oldStatus.canReschedule()) {
            throw new BusinessException("Cannot reschedule appointment with status: " + oldAppointment.getStatus());
        }

        // Cancel old appointment (mark as RESCHEDULED, not CANCELLED)
        oldAppointment.setStatus(AppointmentStatus.RESCHEDULED.name());
        oldAppointment.setUpdatedAt(LocalDateTime.now());
        appointmentMapper.update(oldAppointment);

        // Release old slot: BOOKED -> AVAILABLE
        Slot oldSlot = slotMapper.selectById(oldAppointment.getSlotId());
        if (oldSlot != null) {
            slotMapper.updateStatus(oldSlot.getId(), "AVAILABLE", "BOOKED", oldSlot.getVersion());
        }

        // Decrement old schedule booked count
        scheduleMapper.decrementBookedCount(oldAppointment.getScheduleId());

        // Book the new slot
        BookingRequest bookingRequest = BookingRequest.builder()
                .patientId(oldAppointment.getPatientId())
                .patientName(oldAppointment.getPatientName())
                .slotId(request.getNewSlotId())
                .build();

        Appointment newAppointment = book(bookingRequest);

        // Link new appointment to old one
        newAppointment.setOriginalAppointmentId(oldAppointment.getId());
        newAppointment.setUpdatedAt(LocalDateTime.now());
        appointmentMapper.update(newAppointment);

        auditUtil.log("APPOINTMENT", newAppointment.getId(), AuditAction.RESCHEDULE,
                oldAppointment.getPatientId(),
                "Rescheduled from appointment " + oldAppointment.getId() + " to slot " + request.getNewSlotId());

        log.info("Appointment {} rescheduled to {} for patient {}",
                oldAppointment.getId(), newAppointment.getId(), oldAppointment.getPatientId());

        // Trigger waitlist backfill for the old schedule (slot was freed)
        waitlistService.tryFillFromWaitlist(oldAppointment.getScheduleId());

        return newAppointment;
    }

    @Override
    @Transactional
    public void checkIn(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BusinessException("Appointment not found: " + appointmentId);
        }

        AppointmentStatus status = AppointmentStatus.valueOf(appointment.getStatus());
        if (!status.canCheckIn()) {
            throw new BusinessException("Cannot check in appointment with status: " + appointment.getStatus());
        }

        // Update appointment status
        appointment.setStatus(AppointmentStatus.CHECKED_IN.name());
        appointment.setCheckedInAt(LocalDateTime.now());
        appointment.setUpdatedAt(LocalDateTime.now());
        appointmentMapper.update(appointment);

        // Update slot status: BOOKED -> CHECKED_IN
        Slot slot = slotMapper.selectById(appointment.getSlotId());
        if (slot != null) {
            slotMapper.updateStatus(slot.getId(), "CHECKED_IN", "BOOKED", slot.getVersion());
        }

        auditUtil.log("APPOINTMENT", appointmentId, AuditAction.CHECK_IN,
                appointment.getPatientId(), "Patient checked in");

        log.info("Appointment {} checked in for patient {}", appointmentId, appointment.getPatientId());
    }

    @Override
    @Transactional
    public void pass(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BusinessException("Appointment not found: " + appointmentId);
        }

        AppointmentStatus status = AppointmentStatus.valueOf(appointment.getStatus());
        if (!status.canPass()) {
            throw new BusinessException("Cannot pass appointment with status: " + appointment.getStatus());
        }

        // Update appointment status
        appointment.setStatus(AppointmentStatus.PASSED.name());
        appointment.setUpdatedAt(LocalDateTime.now());
        appointmentMapper.update(appointment);

        // Update slot status: CHECKED_IN -> PASSED
        Slot slot = slotMapper.selectById(appointment.getSlotId());
        if (slot != null) {
            slotMapper.updateStatus(slot.getId(), "PASSED", "CHECKED_IN", slot.getVersion());
        }

        auditUtil.log("APPOINTMENT", appointmentId, AuditAction.PASS,
                appointment.getPatientId(), "Patient passed (missed turn)");

        log.info("Appointment {} marked as passed for patient {}", appointmentId, appointment.getPatientId());
    }

    @Override
    @Transactional
    public void complete(Long appointmentId) {
        Appointment appointment = appointmentMapper.selectById(appointmentId);
        if (appointment == null) {
            throw new BusinessException("Appointment not found: " + appointmentId);
        }

        AppointmentStatus status = AppointmentStatus.valueOf(appointment.getStatus());
        if (!status.canComplete()) {
            throw new BusinessException("Cannot complete appointment with status: " + appointment.getStatus());
        }

        // Update appointment status
        appointment.setStatus(AppointmentStatus.COMPLETED.name());
        appointment.setUpdatedAt(LocalDateTime.now());
        appointmentMapper.update(appointment);

        // Update slot status: CHECKED_IN -> COMPLETED
        Slot slot = slotMapper.selectById(appointment.getSlotId());
        if (slot != null) {
            slotMapper.updateStatus(slot.getId(), "COMPLETED", "CHECKED_IN", slot.getVersion());
        }

        auditUtil.log("APPOINTMENT", appointmentId, AuditAction.COMPLETE,
                appointment.getPatientId(), "Visit completed");

        log.info("Appointment {} completed for patient {}", appointmentId, appointment.getPatientId());
    }

    @Override
    public Appointment getById(Long id) {
        return appointmentMapper.selectById(id);
    }

    @Override
    public List<Appointment> getByPatientId(String patientId) {
        return appointmentMapper.selectByPatientId(patientId);
    }

    @Override
    public List<Appointment> getByScheduleId(Long scheduleId) {
        return appointmentMapper.selectByScheduleId(scheduleId);
    }
}
