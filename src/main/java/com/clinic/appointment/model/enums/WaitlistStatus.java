package com.clinic.appointment.model.enums;

public enum WaitlistStatus {
    /** In queue waiting */
    WAITING,
    /** Slot offered, awaiting patient confirmation */
    OFFERED,
    /** Successfully converted to appointment */
    CONVERTED,
    /** Offer expired or schedule passed */
    EXPIRED,
    /** Patient voluntarily cancelled */
    CANCELLED
}
