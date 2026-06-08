package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("schedule_slot")
public class ScheduleSlot {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scheduleId;
    private Long doctorId;
    private Long departmentId;
    private LocalDate slotDate;
    private LocalTime slotTime;
    private Integer slotNo;
    private String status;
    private Long appointmentId;
    private Integer isExtra;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
