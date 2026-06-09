package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("appointment_resource")
public class AppointmentResource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long appointmentId;
    private Long resourceSlotId;
    private Long resourceId;
    private String resourceType;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
