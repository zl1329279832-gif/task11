package com.clinic.appointment.controller;

import com.clinic.appointment.domain.dto.ApiResponse;
import com.clinic.appointment.domain.dto.SuspendRequest;
import com.clinic.appointment.domain.entity.DoctorSuspension;
import com.clinic.appointment.service.DoctorSuspensionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/suspensions")
@RequiredArgsConstructor
public class SuspensionController {

    private final DoctorSuspensionService suspensionService;

    /** 发布停诊（自动处理受影响预约） */
    @PostMapping
    public ApiResponse<Map<String, Object>> suspend(@RequestBody SuspendRequest request) {
        return ApiResponse.ok(suspensionService.suspend(request));
    }

    /** 查询停诊记录 */
    @GetMapping("/{id}")
    public ApiResponse<DoctorSuspension> getById(@PathVariable Long id) {
        return ApiResponse.ok(suspensionService.getById(id));
    }
}
