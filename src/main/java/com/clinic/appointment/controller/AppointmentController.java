package com.clinic.appointment.controller;

import com.clinic.appointment.domain.dto.*;
import com.clinic.appointment.domain.entity.Appointment;
import com.clinic.appointment.service.AppointmentService;
import com.clinic.appointment.service.MultiResourceBookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final MultiResourceBookingService multiResourceBookingService;

    /** 预约挂号 */
    @PostMapping("/book")
    public ApiResponse<Appointment> book(@Valid @RequestBody BookRequest request) {
        return ApiResponse.ok(appointmentService.book(request));
    }

    /** 取消预约 */
    @PostMapping("/cancel")
    public ApiResponse<Appointment> cancel(@Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(appointmentService.cancel(request));
    }

    /** 改签 */
    @PostMapping("/reschedule")
    public ApiResponse<Appointment> reschedule(@Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.ok(appointmentService.reschedule(request));
    }

    /** 签到 */
    @PostMapping("/{id}/checkin")
    public ApiResponse<Appointment> checkIn(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.checkIn(id));
    }

    /** 过号 */
    @PostMapping("/{id}/missed")
    public ApiResponse<Appointment> markMissed(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.markMissed(id));
    }

    /** 查询预约 */
    @GetMapping("/{id}")
    public ApiResponse<Appointment> getById(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.getById(id));
    }

    /** 患者预约列表 */
    @GetMapping("/patient/{patientId}")
    public ApiResponse<List<Appointment>> getByPatient(@PathVariable Long patientId) {
        return ApiResponse.ok(appointmentService.getByPatient(patientId));
    }

    /** 医生某日预约列表 */
    @GetMapping("/doctor/{doctorId}/date/{date}")
    public ApiResponse<List<Appointment>> getByDoctorAndDate(
            @PathVariable Long doctorId, @PathVariable LocalDate date) {
        return ApiResponse.ok(appointmentService.getByDoctorAndDate(doctorId, date));
    }

    /** 联合预约（检查型门诊） */
    @PostMapping("/joint-book")
    public ApiResponse<JointBookingResult> jointBook(@Valid @RequestBody JointBookRequest request) {
        return ApiResponse.ok(multiResourceBookingService.jointBook(request));
    }

    /** 联合取消 */
    @PostMapping("/joint-cancel")
    public ApiResponse<Appointment> jointCancel(@Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(multiResourceBookingService.jointCancel(request));
    }

    /** 联合改约 */
    @PostMapping("/joint-reschedule")
    public ApiResponse<JointBookingResult> jointReschedule(@Valid @RequestBody JointRescheduleRequest request) {
        return ApiResponse.ok(multiResourceBookingService.jointReschedule(request));
    }
}
