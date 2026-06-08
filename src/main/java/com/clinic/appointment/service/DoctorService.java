package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Doctor;

import java.util.List;

public interface DoctorService {

    Doctor create(Doctor doctor);

    Doctor update(Doctor doctor);

    Doctor getById(Long id);

    Doctor getByCode(String code);

    List<Doctor> getByDepartmentId(Long departmentId);

    List<Doctor> getAll();
}
