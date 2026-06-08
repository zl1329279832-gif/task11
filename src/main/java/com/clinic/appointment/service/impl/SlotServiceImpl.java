package com.clinic.appointment.service.impl;

import com.clinic.appointment.mapper.SlotMapper;
import com.clinic.appointment.model.entity.Slot;
import com.clinic.appointment.service.SlotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    private final SlotMapper slotMapper;

    @Override
    public Slot getById(Long id) {
        return slotMapper.selectById(id);
    }

    @Override
    public List<Slot> getByScheduleId(Long scheduleId) {
        return slotMapper.selectByScheduleId(scheduleId);
    }

    @Override
    public List<Slot> getAvailableByScheduleId(Long scheduleId) {
        return slotMapper.selectAvailableByScheduleId(scheduleId);
    }

    @Override
    public List<Slot> getByDoctorAndDate(Long doctorId, LocalDate date) {
        return slotMapper.selectByDoctorAndDate(doctorId, date);
    }

    @Override
    public boolean compareAndSetStatus(Long slotId, String newStatus, String expectedOldStatus, Integer expectedVersion) {
        int rows = slotMapper.updateStatus(slotId, newStatus, expectedOldStatus, expectedVersion);
        return rows > 0;
    }

    @Override
    public int countByScheduleIdAndStatus(Long scheduleId, String status) {
        return slotMapper.countByScheduleIdAndStatus(scheduleId, status);
    }
}
