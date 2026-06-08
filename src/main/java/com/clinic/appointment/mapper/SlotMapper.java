package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Slot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SlotMapper {

    int insert(Slot slot);

    int batchInsert(@Param("list") List<Slot> list);

    Slot selectById(Long id);

    List<Slot> selectByScheduleId(Long scheduleId);

    List<Slot> selectAvailableByScheduleId(@Param("scheduleId") Long scheduleId);

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("oldStatus") String oldStatus,
                     @Param("version") Integer version);

    List<Slot> selectByDoctorAndDate(@Param("doctorId") Long doctorId,
                                     @Param("scheduleDate") LocalDate scheduleDate);

    int countByScheduleIdAndStatus(@Param("scheduleId") Long scheduleId,
                                   @Param("status") String status);

    Slot selectByIdForUpdate(@Param("id") Long id);
}
