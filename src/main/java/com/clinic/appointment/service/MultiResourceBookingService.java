package com.clinic.appointment.service;

import com.clinic.appointment.domain.dto.*;
import com.clinic.appointment.domain.entity.Appointment;

public interface MultiResourceBookingService {

    JointBookingResult jointBook(JointBookRequest request);

    Appointment jointCancel(CancelRequest request);

    JointBookingResult jointReschedule(JointRescheduleRequest request);
}
