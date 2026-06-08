package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.Holiday;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HolidayMapper extends BaseMapper<Holiday> {
}
