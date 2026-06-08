package com.clinic.appointment.service.impl;

import com.clinic.appointment.mapper.HolidayMapper;
import com.clinic.appointment.model.entity.Holiday;
import com.clinic.appointment.service.HolidayService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class HolidayServiceImpl implements HolidayService {

    private final HolidayMapper holidayMapper;

    @Override
    @Transactional
    public Holiday create(Holiday holiday) {
        holidayMapper.insert(holiday);
        return holiday;
    }

    @Override
    @Transactional
    public void delete(Long id) {
        holidayMapper.delete(id);
    }

    @Override
    public Holiday getById(Long id) {
        return holidayMapper.selectById(id);
    }

    @Override
    public Holiday getByDate(LocalDate date) {
        return holidayMapper.selectByDate(date);
    }

    @Override
    public List<Holiday> getByDateRange(LocalDate start, LocalDate end) {
        return holidayMapper.selectByDateRange(start, end);
    }

    @Override
    public List<Holiday> getAll() {
        return holidayMapper.selectAll();
    }

    @Override
    public boolean isHoliday(LocalDate date) {
        return holidayMapper.selectByDate(date) != null;
    }
}
