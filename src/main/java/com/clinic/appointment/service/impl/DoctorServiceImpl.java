package com.clinic.appointment.service.impl;

import com.clinic.appointment.mapper.DoctorMapper;
import com.clinic.appointment.model.entity.Doctor;
import com.clinic.appointment.service.DoctorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DoctorServiceImpl implements DoctorService {

    private final DoctorMapper doctorMapper;

    @Override
    @Transactional
    public Doctor create(Doctor doctor) {
        doctorMapper.insert(doctor);
        return doctor;
    }

    @Override
    @Transactional
    public Doctor update(Doctor doctor) {
        doctorMapper.update(doctor);
        return doctor;
    }

    @Override
    public Doctor getById(Long id) {
        return doctorMapper.selectById(id);
    }

    @Override
    public Doctor getByCode(String code) {
        return doctorMapper.selectByCode(code);
    }

    @Override
    public List<Doctor> getByDepartmentId(Long departmentId) {
        return doctorMapper.selectByDepartmentId(departmentId);
    }

    @Override
    public List<Doctor> getAll() {
        return doctorMapper.selectAll();
    }
}
