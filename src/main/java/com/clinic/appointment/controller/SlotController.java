package com.clinic.appointment.controller;

import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.SlotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @GetMapping("/schedule/{scheduleId}")
    public Result<List<Slot>> getByScheduleId(@PathVariable Long scheduleId) {
        log.info("Getting slots for schedule: {}", scheduleId);
        return Result.ok(slotService.getByScheduleId(scheduleId));
    }

    @GetMapping("/schedule/{scheduleId}/available")
    public Result<List<Slot>> getAvailable(@PathVariable Long scheduleId) {
        log.info("Getting available slots for schedule: {}", scheduleId);
        return Result.ok(slotService.getAvailableByScheduleId(scheduleId));
    }

    @GetMapping("/doctor/{doctorId}/date/{date}")
    public Result<List<Slot>> getByDoctorAndDate(@PathVariable Long doctorId,
                                                  @PathVariable LocalDate date) {
        log.info("Getting slots for doctor {} on date {}", doctorId, date);
        return Result.ok(slotService.getByDoctorAndDate(doctorId, date));
    }
}
