package com.clinic.appointment.controller;

import com.clinic.appointment.domain.dto.ApiResponse;
import com.clinic.appointment.domain.dto.WaitlistRequest;
import com.clinic.appointment.domain.entity.Waitlist;
import com.clinic.appointment.service.WaitlistService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    /** 加入候补 */
    @PostMapping("/join")
    public ApiResponse<Waitlist> join(@Valid @RequestBody WaitlistRequest request) {
        return ApiResponse.ok(waitlistService.join(request));
    }

    /** 取消候补 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Waitlist> cancel(@PathVariable Long id) {
        return ApiResponse.ok(waitlistService.cancel(id));
    }

    /** 查询患者候补 */
    @GetMapping("/patient/{patientId}")
    public ApiResponse<List<Waitlist>> getByPatient(@PathVariable Long patientId) {
        return ApiResponse.ok(waitlistService.getByPatient(patientId));
    }

    /** 查询某医生某日候补队列 */
    @GetMapping("/doctor/{doctorId}/date/{date}")
    public ApiResponse<List<Waitlist>> getWaiting(
            @PathVariable Long doctorId, @PathVariable LocalDate date) {
        return ApiResponse.ok(waitlistService.getWaitingByDoctorAndDate(doctorId, date));
    }
}
