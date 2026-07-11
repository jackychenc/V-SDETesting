-- TMS Enhancement — initial schema. Implements B2 frozen data model v1.2 (G3-signed, 36c3635a).
-- 18 entities + read models. Keyed to B1 v1.0 ReqIDs. All timestamps UTC (NFR-DATA-01).
-- Owner B3; Flyway migration reviewed by Senior Developer.

-- ---------- M5 Admin & Access (REQ-M5-01/02/03/04) ----------
CREATE TABLE app_user (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(128) NOT NULL UNIQUE,
    display_name VARCHAR(256),
    enabled      BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE role (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(32) NOT NULL UNIQUE      -- VIEWER | TESTER | TEST_LEAD | ADMINISTRATOR
);
CREATE TABLE project (
    id                  BIGSERIAL PRIMARY KEY,
    tms_project_id      VARCHAR(64) NOT NULL UNIQUE,
    polarion_project_id VARCHAR(64) NOT NULL
);
CREATE TABLE project_role_assignment (       -- deny-by-default: no row => no access (REQ-M5-01)
    user_id    BIGINT NOT NULL REFERENCES app_user(id),
    project_id BIGINT NOT NULL REFERENCES project(id),
    role_id    BIGINT NOT NULL REFERENCES role(id),
    PRIMARY KEY (user_id, project_id, role_id)
);
CREATE TABLE audit_event (                    -- REQ-M5-03 (append-only; who/when/what + outcome)
    id        BIGSERIAL PRIMARY KEY,
    actor     VARCHAR(128) NOT NULL,
    action    VARCHAR(64)  NOT NULL,
    target    VARCHAR(512),
    outcome   VARCHAR(8)   NOT NULL,          -- ALLOW | DENY
    at_utc    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    detail    JSONB
);
CREATE INDEX ix_audit_actor_at ON audit_event(actor, at_utc);

CREATE TABLE sync_config (                    -- REQ-M5-04 versioned/revertible
    project_id   BIGINT NOT NULL REFERENCES project(id),
    version      INT    NOT NULL,
    mappings     JSONB  NOT NULL,             -- project + type mappings (ACL)
    field_rules  JSONB  NOT NULL,             -- per-field direction matrix (REQ-M1-03)
    schedule     VARCHAR(32) NOT NULL DEFAULT 'PT15M',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, version)
);

-- ---------- M1 Polarion Sync (REQ-M1-01..06) ----------
CREATE TABLE test_case (                      -- synced (REQ-M1-01/03)
    id               BIGSERIAL PRIMARY KEY,
    project_id       BIGINT NOT NULL REFERENCES project(id),
    polarion_id      VARCHAR(64) NOT NULL,
    title            VARCHAR(512),
    definition       TEXT,
    revision         VARCHAR(64),
    last_common_rev  VARCHAR(64),             -- for conflict detection (REQ-M1-04, S2)
    content_hash     CHAR(64),                -- SHA-256 field-set (B4)
    source_revision  VARCHAR(64),
    last_synced_at   TIMESTAMPTZ,
    UNIQUE (project_id, polarion_id)          -- idempotent upsert key (REQ-M1-06)
);
CREATE TABLE requirement (                    -- synced
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL REFERENCES project(id),
    polarion_id     VARCHAR(64) NOT NULL,
    title           VARCHAR(512),
    revision        VARCHAR(64),
    content_hash    CHAR(64),
    source_revision VARCHAR(64),
    last_synced_at  TIMESTAMPTZ,
    UNIQUE (project_id, polarion_id)
);
CREATE TABLE sync_state (                     -- REQ-M1-02: incremental watermark
    project_id       BIGINT PRIMARY KEY REFERENCES project(id),
    watermark        VARCHAR(64),             -- highest contiguous all-success revision (B4#2)
    overlap_lookback INT NOT NULL DEFAULT 1   -- re-fetch margin (B4#3; sparse-rev caveat)
);
CREATE TABLE sync_run (                       -- REQ-M1-05 health record
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL REFERENCES project(id),
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    duration_ms    BIGINT,
    read_count     INT NOT NULL DEFAULT 0,
    written_count  INT NOT NULL DEFAULT 0,
    failed_count   INT NOT NULL DEFAULT 0,
    expected_delta INT NOT NULL DEFAULT 0,    -- from INDEPENDENT probe (B4#1)
    anomaly_flags  VARCHAR(128) NOT NULL DEFAULT ''  -- csv of zero_change|consecutive_failure|checksum_mismatch
);
CREATE INDEX ix_syncrun_project_started ON sync_run(project_id, started_at DESC);
CREATE TABLE conflict_item (                  -- REQ-M1-04 (S2)
    id             BIGSERIAL PRIMARY KEY,
    sync_run_id    BIGINT REFERENCES sync_run(id),
    item_polarion_id VARCHAR(64) NOT NULL,
    field_diffs    JSONB,
    status         VARCHAR(16) NOT NULL DEFAULT 'open',
    resolution     JSONB
);
CREATE TABLE error_item (                     -- REQ-M1-06 error queue (S2 retry)
    id             BIGSERIAL PRIMARY KEY,
    sync_run_id    BIGINT REFERENCES sync_run(id),
    item_polarion_id VARCHAR(64) NOT NULL,
    message        TEXT,
    retry_count    INT NOT NULL DEFAULT 0,
    status         VARCHAR(16) NOT NULL DEFAULT 'open'
);

-- ---------- M2 Execution / M3 Defect (REQ-M2/M3) ----------
CREATE TABLE test_run (
    id          BIGSERIAL PRIMARY KEY,
    project_id  BIGINT NOT NULL REFERENCES project(id),
    name        VARCHAR(256) NOT NULL,
    state       VARCHAR(16) NOT NULL DEFAULT 'draft',  -- draft->active->completed->closed
    due_date    DATE,
    created_by  VARCHAR(128),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE execution (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT NOT NULL REFERENCES test_run(id),
    test_case_id   BIGINT NOT NULL REFERENCES test_case(id),
    assignee       VARCHAR(128),
    status         VARCHAR(16),               -- pass|fail|blocked|skipped
    per_step_results JSONB,
    actual_text    TEXT,
    executed_at    TIMESTAMPTZ
);
CREATE INDEX ix_execution_run_status ON execution(run_id, status);
CREATE INDEX ix_execution_testcase ON execution(test_case_id);
CREATE TABLE evidence_file (
    id           BIGSERIAL PRIMARY KEY,
    execution_id BIGINT NOT NULL REFERENCES execution(id),
    blob_ref     VARCHAR(512) NOT NULL,       -- object store ref (blob not in DB)
    mime         VARCHAR(128),
    size_bytes   BIGINT CHECK (size_bytes <= 26214400),  -- <=25 MB
    av_scan_status VARCHAR(16) NOT NULL DEFAULT 'pending' -- NFR-SEC-01
);
CREATE TABLE defect (
    id                 BIGSERIAL PRIMARY KEY,
    polarion_id        VARCHAR(64),            -- nullable until written back (REQ-M3-01, S2)
    source_execution_id BIGINT REFERENCES execution(id),
    summary            VARCHAR(512),
    env                VARCHAR(256),
    evidence_refs      JSONB,
    content_hash       CHAR(64)
);
CREATE TABLE defect_link (                     -- M:N (REQ-M3-02)
    defect_id    BIGINT NOT NULL REFERENCES defect(id),
    execution_id BIGINT NOT NULL REFERENCES execution(id),
    PRIMARY KEY (defect_id, execution_id)
);

-- ---------- seed roles ----------
INSERT INTO role(name) VALUES ('VIEWER'),('TESTER'),('TEST_LEAD'),('ADMINISTRATOR');
