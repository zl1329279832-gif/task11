package com.clinic.appointment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.clinic.appointment.domain.entity.ResourceSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Mapper
public interface ResourceSlotMapper extends BaseMapper<ResourceSlot> {

    @Update("UPDATE resource_slot SET booked_count = booked_count + 1, " +
            "status = CASE WHEN booked_count + 1 >= capacity THEN 'FULL' ELSE 'AVAILABLE' END, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{slotId} AND booked_count < capacity " +
            "AND status = 'AVAILABLE' AND version = #{version}")
    int casBook(@Param("slotId") Long slotId, @Param("version") Integer version);

    @Update("UPDATE resource_slot SET booked_count = booked_count - 1, " +
            "status = CASE WHEN status IN ('DISABLED','MAINTENANCE') THEN status " +
            "WHEN booked_count - 1 >= capacity THEN 'FULL' ELSE 'AVAILABLE' END, " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{slotId} AND booked_count > 0 AND version = #{version}")
    int casRelease(@Param("slotId") Long slotId, @Param("version") Integer version);

    @Update("UPDATE resource_slot SET status = 'DISABLED', " +
            "version = version + 1, update_time = NOW() " +
            "WHERE id = #{slotId} AND version = #{version}")
    int casDisable(@Param("slotId") Long slotId, @Param("version") Integer version);

    @Select("SELECT rs.* FROM resource_slot rs " +
            "JOIN resource r ON rs.resource_id = r.id " +
            "WHERE r.type = #{resourceType} AND r.status = 1 " +
            "AND rs.slot_date = #{slotDate} AND rs.start_time <= #{time} AND rs.end_time > #{time} " +
            "AND rs.status = 'AVAILABLE' AND rs.booked_count < rs.capacity " +
            "ORDER BY rs.booked_count ASC, rs.start_time")
    List<ResourceSlot> findAvailableByType(@Param("resourceType") String resourceType,
                                            @Param("slotDate") LocalDate slotDate,
                                            @Param("time") LocalTime time);

    @Select("SELECT rs.* FROM resource_slot rs " +
            "JOIN resource r ON rs.resource_id = r.id " +
            "WHERE r.code = #{resourceCode} AND r.status = 1 " +
            "AND rs.slot_date = #{slotDate} AND rs.start_time <= #{time} AND rs.end_time > #{time} " +
            "AND rs.status = 'AVAILABLE' AND rs.booked_count < rs.capacity " +
            "ORDER BY rs.start_time")
    List<ResourceSlot> findAvailableByCode(@Param("resourceCode") String resourceCode,
                                            @Param("slotDate") LocalDate slotDate,
                                            @Param("time") LocalTime time);

    @Select("SELECT * FROM resource_slot WHERE resource_id = #{resourceId} " +
            "AND slot_date = #{slotDate} AND start_time <= #{time} AND end_time > #{time} " +
            "AND status = 'AVAILABLE' AND booked_count < capacity " +
            "ORDER BY start_time")
    List<ResourceSlot> findAvailableForTime(@Param("resourceId") Long resourceId,
                                             @Param("slotDate") LocalDate slotDate,
                                             @Param("time") LocalTime time);
}
