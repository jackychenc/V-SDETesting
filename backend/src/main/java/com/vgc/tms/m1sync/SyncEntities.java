package com.vgc.tms.m1sync;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entities for M1 read-sync (subset of B2 data model v1.2). Requirement/Defect follow the
 * same synced pattern as {@link SyncEntities.TestCaseEntity} and are added with M2/M3.
 */
public final class SyncEntities {
    private SyncEntities() {}

    /** Project mapping TMS↔Polarion (REQ-M1-01). Enumerated by the scheduled sync (REQ-M1-02). */
    @Entity @Table(name = "project")
    public static class ProjectEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "tms_project_id", nullable = false, unique = true) public String tmsProjectId;
        @Column(name = "polarion_project_id", nullable = false) public String polarionProjectId;
    }

    /** Synced test case (REQ-M1-01/03). Idempotent upsert key = (projectId, polarionId) — REQ-M1-06. */
    @Entity @Table(name = "test_case",
            uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "polarion_id"}))
    public static class TestCaseEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "project_id", nullable = false) public Long projectId;
        @Column(name = "polarion_id", nullable = false) public String polarionId;
        public String title;
        @Column(columnDefinition = "text") public String definition;
        public String revision;
        @Column(name = "content_hash", length = 64) public String contentHash;
        @Column(name = "source_revision") public String sourceRevision;
        @Column(name = "last_synced_at") public Instant lastSyncedAt;
    }

    /** Per-project incremental watermark (REQ-M1-02). watermark = highest contiguous all-success rev (B4#2). */
    @Entity @Table(name = "sync_state")
    public static class SyncStateEntity {
        @Id @Column(name = "project_id") public Long projectId;
        public String watermark;
        @Column(name = "overlap_lookback", nullable = false) public int overlapLookback = 1;
    }

    /** Per-run health record (REQ-M1-05). */
    @Entity @Table(name = "sync_run")
    public static class SyncRunEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @Column(name = "project_id", nullable = false) public Long projectId;
        @Column(name = "started_at", nullable = false) public Instant startedAt = Instant.now();
        @Column(name = "duration_ms") public Long durationMs;
        @Column(name = "read_count", nullable = false) public int read;
        @Column(name = "written_count", nullable = false) public int written;
        @Column(name = "failed_count", nullable = false) public int failed;
        @Column(name = "expected_delta", nullable = false) public int expectedDelta;
        @Column(name = "anomaly_flags", nullable = false) public String anomalyFlags = "";
    }
}
