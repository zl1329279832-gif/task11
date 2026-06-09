package com.clinic.appointment.domain.dto;

import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.domain.entity.AppointmentResource;
import lombok.Data;

import java.util.List;

@Data
public class JointBookingResult {
    private Appointment appointment;
    private List<AppointmentResource> resources;

    public JointBookingResult() {}

    public JointBookingResult(Appointment appointment, List<AppointmentResource> resources) {
        this.appointment = appointment;
        this.resources = resources;
    }
}
