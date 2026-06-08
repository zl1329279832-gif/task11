package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Doctor;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DoctorMapper {

    int insert(Doctor doctor);

    int update(Doctor doctor);

    Doctor selectById(Long id);

    Doctor selectByCode(String code);

    List<Doctor> selectByDepartmentId(Long departmentId);

    List<Doctor> selectAll();
}
