package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("nursing_staff")
public class NursingStaff {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String employeeNo;
    private Long departmentId;
    private String qualification;
    private Integer status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
