package com.clinic.appointment.mapper;

import com.clinic.appointment.model.entity.Suspension;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface SuspensionMapper {

    int insert(Suspension suspension);

    int update(Suspension suspension);

    Suspension selectById(Long id);

    List<Suspension> selectByDoctorAndDate(@Param("doctorId") Long doctorId,
                                           @Param("suspendDate") LocalDate suspendDate);
}
