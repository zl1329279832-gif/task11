package com.clinic.appointment.exception;

public class ConcurrentBookingException extends BusinessException {

    public ConcurrentBookingException() {
        super("Slot is being booked by another user, please retry");
    }
}
