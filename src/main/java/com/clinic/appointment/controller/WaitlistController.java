package com.clinic.appointment.controller;

import com.clinic.appointment.model.dto.WaitlistRequest;
import com.clinic.appointment.model.entity.Waitlist;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.WaitlistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @PostMapping("/join")
    public Result<Waitlist> join(@Valid @RequestBody WaitlistRequest request) {
        log.info("Patient {} joining waitlist for schedule {}", request.getPatientId(), request.getScheduleId());
        return Result.ok(waitlistService.joinWaitlist(request.getPatientId(), request.getPatientName(), request.getScheduleId()));
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(@PathVariable Long id) {
        log.info("Cancelling waitlist entry: {}", id);
        waitlistService.cancelWaitlist(id);
        return Result.ok(null);
    }

    @GetMapping("/patient/{patientId}")
    public Result<List<Waitlist>> getByPatient(@PathVariable String patientId) {
        log.info("Getting waitlist entries for patient: {}", patientId);
        return Result.ok(waitlistService.getByPatientId(patientId));
    }

    @GetMapping("/schedule/{scheduleId}")
    public Result<List<Waitlist>> getBySchedule(@PathVariable Long scheduleId,
                                                 @RequestParam(defaultValue = "WAITING") String status) {
        log.info("Getting waitlist entries for schedule {} with status {}", scheduleId, status);
        return Result.ok(waitlistService.getByScheduleIdAndStatus(scheduleId, status));
    }
}
