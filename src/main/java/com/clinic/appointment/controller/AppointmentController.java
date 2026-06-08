package com.clinic.appointment.controller;

import com.clinic.appointment.model.dto.BookingRequest;
import com.clinic.appointment.model.dto.CancelRequest;
import com.clinic.appointment.model.dto.RescheduleRequest;
import com.clinic.appointment.model.entity.Appointment;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.AppointmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;

    @PostMapping("/book")
    public Result<Appointment> book(@Valid @RequestBody BookingRequest request) {
        log.info("Booking appointment for patient: {}", request.getPatientId());
        return Result.ok(appointmentService.book(request));
    }

    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody CancelRequest request) {
        log.info("Cancelling appointment: {}", request.getAppointmentId());
        appointmentService.cancel(request);
        return Result.ok(null);
    }

    @PostMapping("/reschedule")
    public Result<Appointment> reschedule(@Valid @RequestBody RescheduleRequest request) {
        log.info("Rescheduling appointment: {}", request.getAppointmentId());
        return Result.ok(appointmentService.reschedule(request));
    }

    @PostMapping("/{id}/check-in")
    public Result<Void> checkIn(@PathVariable Long id) {
        log.info("Checking in appointment: {}", id);
        appointmentService.checkIn(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/pass")
    public Result<Void> pass(@PathVariable Long id) {
        log.info("Passing appointment: {}", id);
        appointmentService.pass(id);
        return Result.ok(null);
    }

    @PostMapping("/{id}/complete")
    public Result<Void> complete(@PathVariable Long id) {
        log.info("Completing appointment: {}", id);
        appointmentService.complete(id);
        return Result.ok(null);
    }

    @GetMapping("/{id}")
    public Result<Appointment> getById(@PathVariable Long id) {
        log.info("Getting appointment by id: {}", id);
        return Result.ok(appointmentService.getById(id));
    }

    @GetMapping("/patient/{patientId}")
    public Result<List<Appointment>> getByPatient(@PathVariable String patientId) {
        log.info("Getting appointments for patient: {}", patientId);
        return Result.ok(appointmentService.getByPatientId(patientId));
    }
}
