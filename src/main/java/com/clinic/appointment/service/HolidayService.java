package com.clinic.appointment.service;

import com.clinic.appointment.model.entity.Holiday;

import java.time.LocalDate;
import java.util.List;

public interface HolidayService {

    Holiday create(Holiday holiday);

    void delete(Long id);

    Holiday getById(Long id);

    Holiday getByDate(LocalDate date);

    List<Holiday> getByDateRange(LocalDate start, LocalDate end);

    List<Holiday> getAll();

    boolean isHoliday(LocalDate date);
}
