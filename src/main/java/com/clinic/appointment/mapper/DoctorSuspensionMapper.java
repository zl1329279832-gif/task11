package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.DoctorSuspension;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface DoctorSuspensionMapper extends BaseMapper<DoctorSuspension> {

    /**
     * 查找某医生某日期范围内是否有活跃的停诊记录
     */
    @Select("SELECT * FROM doctor_suspension WHERE doctor_id = #{doctorId} " +
            "AND start_date <= #{date} AND end_date >= #{date} " +
            "AND status IN ('PROCESSING','COMPLETED') LIMIT 1")
    DoctorSuspension findActiveByDoctorAndDate(@Param("doctorId") Long doctorId,
                                                @Param("date") LocalDate date);

    /**
     * 查找某医生某日期范围有重叠的所有停诊记录
     */
    @Select("SELECT * FROM doctor_suspension WHERE doctor_id = #{doctorId} " +
            "AND start_date <= #{end} AND end_date >= #{start} " +
            "AND status IN ('PROCESSING','COMPLETED')")
    List<DoctorSuspension> findOverlapping(@Param("doctorId") Long doctorId,
                                            @Param("start") LocalDate start,
                                            @Param("end") LocalDate end);
}
