package com.clinic.appointment.domain.enums;

public enum ResourceType {
    ROOM("诊室"),
    EQUIPMENT("设备"),
    NURSING("护理");

    private final String desc;

    ResourceType(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
