package com.clinic.appointment.domain.enums;

/**
 * 号源状态机
 * <pre>
 *   AVAILABLE ──book──▶ BOOKED ──checkin──▶ CHECKED_IN (终态)
 *      │                  │
 *      │                  ├──cancel──▶ RELEASED ──backfill──▶ AVAILABLE
 *      │                  │
 *      │                  └──noshow──▶ MISSED (终态)
 *      │
 *      ├──expire──▶ EXPIRED (终态)
 *      │
 *      └──suspend──▶ SUSPENDED (终态，不可被定时任务/候补重新激活)
 *
 *   BOOKED ──suspend──▶ SUSPENDED (终态)
 * </pre>
 */
public enum SlotStatus {
    AVAILABLE("可预约"),
    BOOKED("已预约"),
    CHECKED_IN("已签到"),
    EXPIRED("已过期"),
    RELEASED("已释放"),
    MISSED("过号"),
    SUSPENDED("已停诊");

    private final String desc;

    SlotStatus(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
