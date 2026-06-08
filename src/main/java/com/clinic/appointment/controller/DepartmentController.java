package com.clinic.appointment.controller;

import com.clinic.appointment.model.entity.Department;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @PostMapping("/")
    public Result<Department> create(@Valid @RequestBody Department department) {
        log.info("Creating department: {}", department.getName());
        return Result.ok(departmentService.create(department));
    }

    @PutMapping("/")
    public Result<Department> update(@Valid @RequestBody Department department) {
        log.info("Updating department: {}", department.getId());
        return Result.ok(departmentService.update(department));
    }

    @GetMapping("/{id}")
    public Result<Department> getById(@PathVariable Long id) {
        log.info("Getting department by id: {}", id);
        return Result.ok(departmentService.getById(id));
    }

    @GetMapping("/")
    public Result<List<Department>> getAll() {
        log.info("Getting all departments");
        return Result.ok(departmentService.getAll());
    }
}
