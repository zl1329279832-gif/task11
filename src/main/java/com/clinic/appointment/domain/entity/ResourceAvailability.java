package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("resource_availability")
public class ResourceAvailability {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String resourceType;
    private Long resourceId;
    private LocalDate availDate;
    private LocalTime startTime;
    private LocalTime endTime;
    private String status;
    private Long appointmentId;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
