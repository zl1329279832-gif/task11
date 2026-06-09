package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.ExamType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ExamTypeMapper extends BaseMapper<ExamType> {

    @Select("SELECT * FROM exam_type WHERE code = #{code} AND status = 1")
    ExamType findByCode(@Param("code") String code);
}
