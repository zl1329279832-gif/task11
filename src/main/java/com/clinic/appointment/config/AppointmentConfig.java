package com.clinic.appointment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "appointment")
public class AppointmentConfig {

    private int maxWaitlistSize = 10;
    private int autoCancelMinutes = 30;
    private int passRecallMinutes = 15;
    private int lockWaitSeconds = 5;
}
