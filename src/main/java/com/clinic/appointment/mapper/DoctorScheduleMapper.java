package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.DoctorSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DoctorScheduleMapper extends BaseMapper<DoctorSchedule> {

    @Select("SELECT * FROM doctor_schedule WHERE doctor_id = #{doctorId} " +
            "AND schedule_date BETWEEN #{start} AND #{end}")
    List<DoctorSchedule> findByDoctorAndDateRange(@Param("doctorId") Long doctorId,
                                                   @Param("start") LocalDate start,
                                                   @Param("end") LocalDate end);

    @Update("UPDATE doctor_schedule SET status = #{status}, update_time = NOW() " +
            "WHERE doctor_id = #{doctorId} AND schedule_date >= #{fromDate} AND status = 'NORMAL'")
    int batchUpdateStatus(@Param("doctorId") Long doctorId,
                          @Param("fromDate") LocalDate fromDate,
                          @Param("status") String status);
}
