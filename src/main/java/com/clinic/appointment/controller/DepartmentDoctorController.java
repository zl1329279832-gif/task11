package com.clinic.appointment.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.clinic.appointment.domain.dto.ApiResponse;
import com.clinic.appointment.domain.entity.Department;
import com.clinic.appointment.domain.entity.Doctor;
import com.clinic.appointment.domain.entity.Holiday;
import com.clinic.appointment.mapper.DepartmentMapper;
import com.clinic.appointment.mapper.DoctorMapper;
import com.clinic.appointment.mapper.HolidayMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DepartmentDoctorController {

    private final DepartmentMapper departmentMapper;
    private final DoctorMapper doctorMapper;
    private final HolidayMapper holidayMapper;

    // ── 科室 ──────────────────────────────────

    @PostMapping("/departments")
    public ApiResponse<Department> createDepartment(@RequestBody Department dept) {
        departmentMapper.insert(dept);
        return ApiResponse.ok(dept);
    }

    @GetMapping("/departments")
    public ApiResponse<List<Department>> listDepartments() {
        return ApiResponse.ok(departmentMapper.selectList(null));
    }

    @GetMapping("/departments/{id}")
    public ApiResponse<Department> getDepartment(@PathVariable Long id) {
        return ApiResponse.ok(departmentMapper.selectById(id));
    }

    // ── 医生 ──────────────────────────────────

    @PostMapping("/doctors")
    public ApiResponse<Doctor> createDoctor(@RequestBody Doctor doctor) {
        doctorMapper.insert(doctor);
        return ApiResponse.ok(doctor);
    }

    @GetMapping("/doctors")
    public ApiResponse<List<Doctor>> listDoctors(
            @RequestParam(required = false) Long departmentId) {
        if (departmentId != null) {
            return ApiResponse.ok(doctorMapper.findActiveByDepartment(departmentId));
        }
        return ApiResponse.ok(doctorMapper.selectList(null));
    }

    @GetMapping("/doctors/{id}")
    public ApiResponse<Doctor> getDoctor(@PathVariable Long id) {
        return ApiResponse.ok(doctorMapper.selectById(id));
    }

    // ── 节假日 ────────────────────────────────

    @PostMapping("/holidays")
    public ApiResponse<Holiday> createHoliday(@RequestBody Holiday holiday) {
        holidayMapper.insert(holiday);
        return ApiResponse.ok(holiday);
    }

    @GetMapping("/holidays")
    public ApiResponse<List<Holiday>> listHolidays() {
        return ApiResponse.ok(holidayMapper.selectList(null));
    }
}
