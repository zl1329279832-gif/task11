package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Waitlist;
import java.util.List;

public interface WaitlistService {
    Waitlist joinWaitlist(String patientId, String patientName, Long scheduleId);
    void cancelWaitlist(Long waitlistId);

    /** Core: try to fill a cancelled slot from the waitlist queue. Called after cancel. */
    boolean tryFillFromWaitlist(Long scheduleId);

    List<Waitlist> getByPatientId(String patientId);
    List<Waitlist> getByScheduleIdAndStatus(Long scheduleId, String status);
}
