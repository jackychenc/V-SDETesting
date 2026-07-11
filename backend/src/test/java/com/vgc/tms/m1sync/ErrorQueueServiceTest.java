package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.ErrorItemEntity;
import com.vgc.tms.m1sync.SyncRepositories.ErrorItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Error queue tests (REQ-M1-06, §E4) — failed items enqueued + retried w/ backoff, never dropped;
 * terminal after MAX_RETRIES but STILL in the queue (visible to operator).
 */
class ErrorQueueServiceTest {

    private ErrorItemRepository errors;
    private ErrorQueueService svc;

    @BeforeEach
    void setup() {
        errors = mock(ErrorItemRepository.class);
        when(errors.save(any())).thenAnswer(a -> a.getArgument(0));
        svc = new ErrorQueueService(errors);
    }

    @Test
    void enqueue_recordsOpenItem_neverDropped() {
        ErrorItemEntity e = svc.enqueue(10L, "A", "boom");
        assertEquals("open", e.status);
        assertEquals("A", e.itemPolarionId);
        verify(errors).save(any());
    }

    @Test
    void failedRetry_incrementsCount_thenTerminalButNotDropped() {
        ErrorItemEntity e = new ErrorItemEntity(); e.itemPolarionId = "A"; e.status = "open";
        for (int i = 0; i < ErrorQueueService.MAX_RETRIES; i++) svc.recordAttempt(e, false);
        assertEquals(ErrorQueueService.MAX_RETRIES, e.retryCount);
        assertEquals("terminal", e.status, "exhausted retries → terminal, but NOT deleted (still surfaced)");
    }

    @Test
    void successfulRetry_resolves() {
        ErrorItemEntity e = new ErrorItemEntity(); e.status = "retrying"; e.retryCount = 2;
        svc.recordAttempt(e, true);
        assertEquals("resolved", e.status);
    }

    @Test
    void backoff_isExponential() {
        assertEquals(Duration.ofSeconds(30), ErrorQueueService.backoffFor(0));
        assertEquals(Duration.ofSeconds(60), ErrorQueueService.backoffFor(1));
        assertEquals(Duration.ofSeconds(120), ErrorQueueService.backoffFor(2));
    }
}
