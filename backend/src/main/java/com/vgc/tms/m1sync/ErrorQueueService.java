package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.ErrorItemEntity;
import com.vgc.tms.m1sync.SyncRepositories.ErrorItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Error queue (REQ-M1-06, contract v1.3 §E4). Failed sync items are ENQUEUED here — never silently
 * dropped — and retried with exponential backoff up to {@link #MAX_RETRIES}, after which they go
 * {@code terminal} and stay visible in the error queue (surfaced via GET /api/sync/errors) for
 * operator action. This is the "nothing dropped silently" guarantee behind REQ-M1-06.
 */
@Service
public class ErrorQueueService {

    static final int MAX_RETRIES = 5;
    static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    private final ErrorItemRepository errors;

    public ErrorQueueService(ErrorItemRepository errors) {
        this.errors = errors;
    }

    /** Enqueue a failed item (idempotent-ish: one open row per item is enough for the pilot). */
    @Transactional
    public ErrorItemEntity enqueue(Long syncRunId, String polarionId, String message) {
        ErrorItemEntity e = new ErrorItemEntity();
        e.syncRunId = syncRunId;
        e.itemPolarionId = polarionId;
        e.message = message;
        e.status = "open";
        return errors.save(e);
    }

    /** Backoff for the next attempt: exponential 30s, 60s, 120s… (capped by MAX_RETRIES). */
    public static Duration backoffFor(int retryCount) {
        return BASE_BACKOFF.multipliedBy(1L << Math.min(retryCount, 10));
    }

    /**
     * Record a retry attempt outcome. On success → resolved. On failure → increment retryCount;
     * once it reaches MAX_RETRIES the item is {@code terminal} (still in the queue, NOT dropped).
     */
    @Transactional
    public ErrorItemEntity recordAttempt(ErrorItemEntity e, boolean succeeded) {
        if (succeeded) {
            e.status = "resolved";
        } else {
            e.retryCount++;
            e.status = e.retryCount >= MAX_RETRIES ? "terminal" : "retrying";
        }
        return errors.save(e);
    }

    /** Items still needing attention (open/retrying/terminal) — surfaced to the operator, never lost. */
    @Transactional(readOnly = true)
    public List<ErrorItemEntity> outstanding() {
        return errors.findByStatusIn(List.of("open", "retrying", "terminal"));
    }
}
