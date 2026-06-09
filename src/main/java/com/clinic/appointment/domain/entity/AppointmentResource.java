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
    private String resourceType;
    private Long resourceId;
    private Long availabilityId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
