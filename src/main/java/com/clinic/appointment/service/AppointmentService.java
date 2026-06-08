package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.dto.*;
import java.util.List;

public interface AppointmentService {
    /** Book with Redis distributed lock + optimistic lock */
    Appointment book(BookingRequest request);

    /** Cancel and trigger waitlist backfill */
    void cancel(CancelRequest request);

    /** Reschedule = cancel old + book new, transactional */
    Appointment reschedule(RescheduleRequest request);

    /** Patient check-in */
    void checkIn(Long appointmentId);

    /** Mark patient as passed (missed turn) */
    void pass(Long appointmentId);

    /** Complete visit */
    void complete(Long appointmentId);

    Appointment getById(Long id);
    List<Appointment> getByPatientId(String patientId);
    List<Appointment> getByScheduleId(Long scheduleId);
}
