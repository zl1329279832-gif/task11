package com.clinic.appointment.domain.enums;

/**
 * 号源状态机
 * <pre>
 *   AVAILABLE ──book──▶ BOOKED ──checkin──▶ CHECKED_IN (终态)
 *      │                  │                     │
 *      │                  ├──cancel──▶ RELEASED  │
 *      │                  │                      │
 *      │                  └──noshow──▶ MISSED (终态)
 *      │
 *      ├──expire──▶ EXPIRED (终态)
 *      │
 *      └──suspend──▶ RELEASED
 * </pre>
 */
public enum SlotStatus {
    AVAILABLE("可预约"),
    BOOKED("已预约"),
    CHECKED_IN("已签到"),
    EXPIRED("已过期"),
    RELEASED("已释放"),
    MISSED("过号");

    private final String desc;

    SlotStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
