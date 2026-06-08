package com.clinic.appointment.controller;

import com.clinic.appointment.model.dto.ExtraSlotRequest;
import com.clinic.appointment.model.dto.ScheduleGenerateRequest;
import com.clinic.appointment.model.entity.Schedule;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/schedules")
@RequiredArgsConstructor
public class ScheduleController {

    private final ScheduleService scheduleService;

    @PostMapping("/generate")
    public Result<Integer> generate(@Valid @RequestBody ScheduleGenerateRequest request) {
        log.info("Generating schedules from {} to {}", request.getStartDate(), request.getEndDate());
        int count = scheduleService.generateSchedules(request.getStartDate(), request.getEndDate());
        return Result.ok(count);
    }

    @GetMapping("/{id}")
    public Result<Schedule> getById(@PathVariable Long id) {
        log.info("Getting schedule by id: {}", id);
        return Result.ok(scheduleService.getById(id));
    }

    @GetMapping("/")
    public Result<List<Schedule>> getByDateRange(@RequestParam LocalDate startDate,
                                                  @RequestParam LocalDate endDate) {
        log.info("Getting schedules from {} to {}", startDate, endDate);
        return Result.ok(scheduleService.getByDateRange(startDate, endDate));
    }

    @GetMapping("/doctor/{doctorId}")
    public Result<List<Schedule>> getByDoctor(@PathVariable Long doctorId,
                                               @RequestParam LocalDate startDate,
                                               @RequestParam LocalDate endDate) {
        log.info("Getting schedules for doctor {} from {} to {}", doctorId, startDate, endDate);
        return Result.ok(scheduleService.getByDoctorAndDateRange(doctorId, startDate, endDate));
    }

    @PostMapping("/extra-slots")
    public Result<Void> addExtraSlots(@Valid @RequestBody ExtraSlotRequest request) {
        log.info("Adding {} extra slots to schedule {}", request.getCount(), request.getScheduleId());
        scheduleService.addExtraSlots(request.getScheduleId(), request.getCount());
        return Result.ok(null);
    }
}
