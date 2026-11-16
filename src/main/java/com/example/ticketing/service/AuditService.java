package com.example.ticketing.service;

import com.example.ticketing.entity.AuditLog;
import com.example.ticketing.enums.AuditAction;
import com.example.ticketing.repository.AuditLogRepository;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
public class AuditService {

    private static final int MAX_USER_AGENT = 512;

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(Long actorId, AuditAction action, String resourceType, Object resourceId) {
        String ip = null;
        String userAgent = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            ip = request.getRemoteAddr();
            userAgent = truncate(request.getHeader("User-Agent"));
        }
        repository.save(new AuditLog(actorId, action, resourceType,
                resourceId == null ? null : String.valueOf(resourceId), ip, userAgent));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_USER_AGENT ? value : value.substring(0, MAX_USER_AGENT);
    }
}
