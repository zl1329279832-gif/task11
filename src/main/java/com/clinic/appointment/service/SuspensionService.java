package com.clinic.appointment.service;

import com.clinic.appointment.model.dto.SuspensionRequest;
import com.clinic.appointment.model.entity.Suspension;

public interface SuspensionService {
    /** Suspend doctor and handle affected appointments */
    Suspension suspend(SuspensionRequest request);
}
