package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("schedule_template")
public class ScheduleTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long doctorId;
    private Integer dayOfWeek;
    private String timePeriod;
    private Integer totalSlots;
    private Integer slotInterval;
    private LocalTime startTime;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
