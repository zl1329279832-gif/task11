package com.clinic.appointment.controller;

import com.clinic.appointment.model.entity.AuditLog;
import com.clinic.appointment.model.vo.Result;
import com.clinic.appointment.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping("/entity/{entityType}/{entityId}")
    public Result<List<AuditLog>> getByEntity(@PathVariable String entityType,
                                               @PathVariable Long entityId) {
        log.info("Getting audit logs for entity {}/{}", entityType, entityId);
        return Result.ok(auditService.getByEntity(entityType, entityId));
    }

    @GetMapping("/recent")
    public Result<List<AuditLog>> getRecent(@RequestParam(defaultValue = "50") Integer limit) {
        log.info("Getting {} recent audit logs", limit);
        return Result.ok(auditService.getRecent(limit));
    }
}
