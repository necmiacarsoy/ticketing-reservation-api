package com.example.ticketing.repository;

import java.util.List;

import com.example.ticketing.entity.AuditLog;
import com.example.ticketing.enums.AuditAction;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByActorIdOrderByIdAsc(Long actorId);

    List<AuditLog> findByActionAndResourceTypeAndResourceId(AuditAction action, String resourceType,
                                                            String resourceId);
}
