package com.clinic.appointment.controller;

import com.clinic.appointment.model.entity.Holiday;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.HolidayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @PostMapping("/")
    public Result<Holiday> create(@Valid @RequestBody Holiday holiday) {
        log.info("Creating holiday: {}", holiday.getName());
        return Result.ok(holidayService.create(holiday));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("Deleting holiday: {}", id);
        holidayService.delete(id);
        return Result.ok(null);
    }

    @GetMapping("/")
    public Result<List<Holiday>> getAll() {
        log.info("Getting all holidays");
        return Result.ok(holidayService.getAll());
    }

    @GetMapping("/check")
    public Result<Boolean> isHoliday(@RequestParam LocalDate date) {
        log.info("Checking if {} is a holiday", date);
        return Result.ok(holidayService.isHoliday(date));
    }
}
