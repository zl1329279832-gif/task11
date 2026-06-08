package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.Doctor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface DoctorMapper extends BaseMapper<Doctor> {

    @Select("SELECT * FROM doctor WHERE department_id = #{deptId} AND status = 1")
    List<Doctor> findActiveByDepartment(@Param("deptId") Long departmentId);
}
