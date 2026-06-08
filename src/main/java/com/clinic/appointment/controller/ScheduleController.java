package com.clinic.appointment.controller;

import com.clinic.appointment.domain.dto.ApiResponse;
import com.clinic.appointment.domain.dto.ExtraSlotRequest;
import com.clinic.appointment.domain.dto.ScheduleGenerateRequest;
import com.clinic.appointment.domain.entity.DoctorSchedule;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import com.clinic.appointment.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    /** 根据模板生成排班 */
    @PostMapping("/generate")
    public ApiResponse<List<DoctorSchedule>> generate(@RequestBody ScheduleGenerateRequest request) {
        return ApiResponse.ok(scheduleService.generateSchedule(request));
    }

    /** 临时加号 */
    @PostMapping("/extra-slot")
    public ApiResponse<ScheduleSlot> addExtraSlot(@RequestBody ExtraSlotRequest request) {
        return ApiResponse.ok(scheduleService.addExtraSlot(request));
    }

    /** 查询排班 */
    @GetMapping("/doctor/{doctorId}")
    public ApiResponse<List<DoctorSchedule>> getSchedules(
            @PathVariable Long doctorId,
            @RequestParam LocalDate start,
            @RequestParam LocalDate end) {
        return ApiResponse.ok(scheduleService.getSchedules(doctorId, start, end));
    }

    /** 查询可用号源 */
    @GetMapping("/slots/doctor/{doctorId}/date/{date}")
    public ApiResponse<List<ScheduleSlot>> getAvailableSlots(
            @PathVariable Long doctorId, @PathVariable LocalDate date) {
        return ApiResponse.ok(scheduleService.getSlotsByDoctorAndDate(doctorId, date));
    }

    /** 节假日停诊 */
    @PostMapping("/holiday-suspend")
    public ApiResponse<Integer> applyHoliday(
            @RequestParam LocalDate date,
            @RequestParam(required = false) Long departmentId) {
        return ApiResponse.ok(scheduleService.applyHolidaySuspension(date, departmentId));
    }
}
