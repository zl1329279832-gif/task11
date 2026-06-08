package com.clinic.appointment.service.impl;

import com.clinic.appointment.mapper.DepartmentMapper;
import com.clinic.appointment.model.entity.Department;
import com.clinic.appointment.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentMapper departmentMapper;

    @Override
    @Transactional
    public Department create(Department department) {
        departmentMapper.insert(department);
        return department;
    }

    @Override
    @Transactional
    public Department update(Department department) {
        departmentMapper.update(department);
        return department;
    }

    @Override
    public Department getById(Long id) {
        return departmentMapper.selectById(id);
    }

    @Override
    public List<Department> getAll() {
        return departmentMapper.selectAll();
    }

    @Override
    public Department getByCode(String code) {
        return departmentMapper.selectByCode(code);
    }
}
