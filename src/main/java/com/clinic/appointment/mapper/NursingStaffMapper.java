package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.NursingStaff;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NursingStaffMapper extends BaseMapper<NursingStaff> {

    @Select("SELECT * FROM nursing_staff WHERE department_id = #{deptId} AND status = 1")
    List<NursingStaff> findActiveByDepartment(@Param("deptId") Long deptId);
}
