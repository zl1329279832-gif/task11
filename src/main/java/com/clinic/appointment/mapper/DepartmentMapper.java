package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Department;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface DepartmentMapper {

    int insert(Department department);

    int update(Department department);

    Department selectById(Long id);

    List<Department> selectAll();

    Department selectByCode(String code);
}
