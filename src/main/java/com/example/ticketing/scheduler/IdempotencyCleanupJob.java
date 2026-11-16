package com.example.ticketing.scheduler;

import com.example.ticketing.service.IdempotencyService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService service;

    public IdempotencyCleanupJob(IdempotencyService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    public void purge() {
        long removed = service.purgeExpired();
        if (removed > 0) {
            LOG.info("Purged {} expired idempotency records", removed);
        }
    }
}
