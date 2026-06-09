package com.clinic.appointment.controller;

import com.clinic.appointment.domain.dto.ApiResponse;
import com.clinic.appointment.domain.dto.EquipmentDeactivateRequest;
import com.clinic.appointment.service.ResourceAvailabilityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceAvailabilityService resourceAvailabilityService;

    /** 生成资源可用性窗口 */
    @PostMapping("/generate")
    public ApiResponse<Integer> generateAvailability(
            @RequestParam Long departmentId,
            @RequestParam LocalDate date,
            @RequestParam(defaultValue = "MORNING") String timePeriod) {
        return ApiResponse.ok(resourceAvailabilityService.generateAvailability(departmentId, date, timePeriod));
    }

    /** 设备停用 */
    @PostMapping("/equipment/deactivate")
    public ApiResponse<Map<String, Object>> deactivateEquipment(
            @Valid @RequestBody EquipmentDeactivateRequest request) {
        return ApiResponse.ok(resourceAvailabilityService.deactivateEquipment(request));
    }
}
