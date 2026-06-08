package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("holiday")
public class Holiday {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate holidayDate;
    private String name;
    private String scope;
    private Long departmentId;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
