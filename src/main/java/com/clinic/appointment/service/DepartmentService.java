package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Department;

import java.util.List;

public interface DepartmentService {

    Department create(Department department);

    Department update(Department department);

    Department getById(Long id);

    List<Department> getAll();

    Department getByCode(String code);
}
