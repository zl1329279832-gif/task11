package com.clinic.appointment.domain.enums;

/**
 * 资源类型
 */
public enum ResourceType {
    EXAM_ROOM("检查室"),
    EQUIPMENT("设备"),
    NURSING_STAFF("护理人员");

    private final String desc;

    ResourceType(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
