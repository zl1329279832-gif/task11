package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("appointment")
public class Appointment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appointmentNo;
    private Long patientId;
    private String patientName;
    private Long doctorId;
    private Long departmentId;
    private Long slotId;
    private LocalDate slotDate;
    private LocalTime slotTime;
    private String status;
    private String source;
    private Long originalId;
    private String cancelReason;
    private String appointmentType;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
