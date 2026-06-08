package com.clinic.appointment.controller;

import com.clinic.appointment.model.dto.SuspensionRequest;
import com.clinic.appointment.model.entity.Suspension;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.SuspensionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

@Slf4j
@RestController
@RequestMapping("/api/suspensions")
@RequiredArgsConstructor
public class SuspensionController {

    private final SuspensionService suspensionService;

    @PostMapping("/")
    public Result<Suspension> suspend(@Valid @RequestBody SuspensionRequest request) {
        log.info("Creating suspension for doctor: {}", request.getDoctorId());
        return Result.ok(suspensionService.suspend(request));
    }
}
