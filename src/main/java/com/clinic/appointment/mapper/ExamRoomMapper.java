package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.ExamRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExamRoomMapper extends BaseMapper<ExamRoom> {

    @Select("SELECT * FROM exam_room WHERE department_id = #{deptId} AND status = 'ACTIVE'")
    List<ExamRoom> findActiveByDepartment(@Param("deptId") Long deptId);
}
