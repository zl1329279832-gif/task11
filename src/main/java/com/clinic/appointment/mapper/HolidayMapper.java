package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Holiday;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface HolidayMapper {

    int insert(Holiday holiday);

    int delete(@Param("id") Long id);

    Holiday selectById(Long id);

    Holiday selectByDate(@Param("holidayDate") LocalDate holidayDate);

    List<Holiday> selectByDateRange(@Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate);

    List<Holiday> selectAll();
}
