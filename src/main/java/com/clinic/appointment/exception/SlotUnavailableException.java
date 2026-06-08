package com.clinic.appointment.exception;

public class SlotUnavailableException extends BusinessException {

    public SlotUnavailableException(Long slotId) {
        super("Slot " + slotId + " is not available");
    }
}
