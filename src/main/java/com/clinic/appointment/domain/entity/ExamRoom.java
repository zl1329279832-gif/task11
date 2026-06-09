package com.clinic.appointment.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("exam_room")
public class ExamRoom {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String code;
    private Long departmentId;
    private String location;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
