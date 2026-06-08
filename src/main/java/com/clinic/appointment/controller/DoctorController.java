package com.clinic.appointment.controller;

import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.DoctorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/doctors")
@RequiredArgsConstructor
public class DoctorController {

    private final DoctorService doctorService;

    @PostMapping("/")
    public Result<Doctor> create(@Valid @RequestBody Doctor doctor) {
        log.info("Creating doctor: {}", doctor.getName());
        return Result.ok(doctorService.create(doctor));
    }

    @PutMapping("/")
    public Result<Doctor> update(@Valid @RequestBody Doctor doctor) {
        log.info("Updating doctor: {}", doctor.getId());
        return Result.ok(doctorService.update(doctor));
    }

    @GetMapping("/{id}")
    public Result<Doctor> getById(@PathVariable Long id) {
        log.info("Getting doctor by id: {}", id);
        return Result.ok(doctorService.getById(id));
    }

    @GetMapping("/")
    public Result<List<Doctor>> getAll() {
        log.info("Getting all doctors");
        return Result.ok(doctorService.getAll());
    }

    @GetMapping("/department/{departmentId}")
    public Result<List<Doctor>> getByDepartment(@PathVariable Long departmentId) {
        log.info("Getting doctors by department: {}", departmentId);
        return Result.ok(doctorService.getByDepartmentId(departmentId));
    }
}
