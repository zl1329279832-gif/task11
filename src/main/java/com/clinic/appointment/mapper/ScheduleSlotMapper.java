package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.ScheduleSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface ScheduleSlotMapper extends BaseMapper<ScheduleSlot> {

    /**
     * 原子CAS预约号源
     */
    @Update("UPDATE schedule_slot SET status = 'BOOKED', appointment_id = #{appointmentId}, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{slotId} AND status = 'AVAILABLE' AND version = #{version}")
    int casBook(@Param("slotId") Long slotId,
                @Param("appointmentId") Long appointmentId,
                @Param("version") Integer version);

    /**
     * 原子CAS释放号源
     */
    @Update("UPDATE schedule_slot SET status = 'AVAILABLE', appointment_id = NULL, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{slotId} AND status = 'BOOKED' AND version = #{version}")
    int casRelease(@Param("slotId") Long slotId, @Param("version") Integer version);

    /**
     * 查找某日某医生可用号源
     */
    @Select("SELECT * FROM schedule_slot WHERE doctor_id = #{doctorId} " +
            "AND slot_date = #{slotDate} AND status = 'AVAILABLE' ORDER BY slot_time")
    List<ScheduleSlot> findAvailable(@Param("doctorId") Long doctorId,
                                      @Param("slotDate") LocalDate slotDate);

    /**
     * 批量释放某医生指定日期范围的号源
     */
    @Update("UPDATE schedule_slot SET status = 'RELEASED', update_time = NOW(), version = version + 1 " +
            "WHERE doctor_id = #{doctorId} AND slot_date BETWEEN #{start} AND #{end} " +
            "AND status = 'AVAILABLE'")
    int batchRelease(@Param("doctorId") Long doctorId,
                     @Param("start") LocalDate start,
                     @Param("end") LocalDate end);

    /**
     * 查找某医生某日的已预约号源（用于停诊处理）
     */
    @Select("SELECT * FROM schedule_slot WHERE doctor_id = #{doctorId} " +
            "AND slot_date BETWEEN #{start} AND #{end} AND status = 'BOOKED'")
    List<ScheduleSlot> findBookedInRange(@Param("doctorId") Long doctorId,
                                          @Param("start") LocalDate start,
                                          @Param("end") LocalDate end);
}
