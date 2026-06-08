package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Slot;
import java.time.LocalDate;
import java.util.List;

public interface SlotService {
    Slot getById(Long id);
    List<Slot> getByScheduleId(Long scheduleId);
    List<Slot> getAvailableByScheduleId(Long scheduleId);
    List<Slot> getByDoctorAndDate(Long doctorId, LocalDate date);

    /**
     * Attempt to CAS-update slot status using optimistic locking.
     * Returns true if update succeeded, false if concurrent modification.
     */
    boolean compareAndSetStatus(Long slotId, String newStatus, String expectedOldStatus, Integer expectedVersion);

    int countByScheduleIdAndStatus(Long scheduleId, String status);
}
