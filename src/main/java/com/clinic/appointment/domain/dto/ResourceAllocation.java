package com.clinic.appointment.domain.dto;

import com.clinic.appointment.domain.entity.ResourceSlot;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Data
public class ResourceAllocation {
    private ResourceSlot roomSlot;
    private ResourceSlot equipmentSlot;
    private ResourceSlot nursingSlot;

    public List<Long> getAllResourceSlotIds() {
        List<Long> ids = new ArrayList<>();
        if (roomSlot != null) ids.add(roomSlot.getId());
        if (equipmentSlot != null) ids.add(equipmentSlot.getId());
        if (nursingSlot != null) ids.add(nursingSlot.getId());
        return ids;
    }

    public List<String> getAllLockKeys() {
        return getAllResourceSlotIds().stream()
                .map(id -> "lock:resource:" + id)
                .collect(Collectors.toList());
    }

    public boolean isEmpty() {
        return roomSlot == null && equipmentSlot == null && nursingSlot == null;
    }
}
