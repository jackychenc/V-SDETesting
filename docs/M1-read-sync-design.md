# PR#3 (planned) `feat/m1-read-sync` — M1 Read-Sync Technical Design DRAFT (B3, Sprint-1)

**Task:** #6 · **Status:** DESIGN DRAFT (per Cindy — design now, **implementation waits on #5 G3 contract freeze**).
**Inputs:** B2 IF-1 contract + data model **v1.2** (36c3635a) · B4 reconcile/read-model build spec (a30ab3fe) · B1 REQ-M1-01/02 (v1.0) · C4 threat model (M1-T1/T2).
**Scope (Sprint-1):** READ direction only (Polarion→TMS) + sync-health record. Bidirectional write-back + conflict queue = Sprint-2.

## 1. Scope → ReqID / AC
| ReqID | What | AC |
|---|---|---|
| REQ-M1-01 | Connect to Polarion (REST) + configurable project/type mapping (ACL) | needs frozen IF-1 contract (G3) |
| REQ-M1-02 | Scheduled (15-min) + on-demand **incremental** read-sync; only changed items; per-run health record | **[REQ-M1-02-AC1]** (2nd sync transfers only deltas + health record written); maps **TS-B-01** |

## 2. Components (backend `m1sync`)
- **`PolarionClient`** (interface) — read work items + revisions via REST (contract v1.2). Impl `MockPolarionClient` (pilot, OUT-02) + `RestPolarionClient` (sandbox/live, same contract). Creds from secret store (M1-T1); TLS (M1-T2).
- **`AntiCorruptionMapper`** — Polarion work item ↔ TMS entity (TestCase/Requirement/Defect), config-driven `projectMapping` + `typeMapping` (REQ-M1-01).
- **`SyncWorker`** — scheduled (`@Scheduled`, `tms.polarion.sync.interval` default PT15M) + on-demand (`POST /sync/{proj}/run`). Isolated from request path (ADR-2). Read-sync algorithm §3.
- **`SyncStateRepository`** — persists per-project **watermark** (Polarion revision, monotonic; B4 §: `SyncState.watermark + overlapLookback`).
- **`SyncRunRepository`** — per-run health record: items read/written/failed + duration + `anomalyFlags` (B4 enum) + `expectedDelta`.
- **Internal Sync API** (exposed to UI/admin/tests) — REQ-M1-02/05/06, per B2 §A4:
  `POST /api/sync/{proj}/run` · `GET /api/sync/{proj}/status` · `GET /api/sync/{proj}/reconcile` · `GET|POST /api/sync/conflicts` · `GET /api/sync/errors` · `GET|PUT /api/sync/config/{proj}`.
  (S1 implements run/status; reconcile/conflicts/errors wired in S2 with bidi + reconciler.)

## 3. Read-sync algorithm (REQ-M1-02, incremental, idempotent)
```
1. read watermark = SyncState[proj].watermark   (0 on first run → full read)
2. expectedDelta  = PolarionClient.countChangedSince(sinceRev)   // ⚠️ B4#1: GENUINELY INDEPENDENT
                    //   count/query call — NOT fetchChangedSince(...).size(). Separate read path so a
                    //   "success/writes-nothing" failure can't suppress BOTH (else zero_change never fires).
3. items          = PolarionClient.fetchChangedSince(sinceRev)   // sinceRev = watermark - overlapLookback (see note)
4. for each item: map via ACL → idempotent upsert keyed on polarionId          // safe re-run (REQ-M1-06)
                  set contentHash, sourceRevision, lastSyncedAt (B4 fields); track per-item success/fail
5. advance watermark:  // ⚠️ B4#2: do NOT jump past failures (REQ-M1-06 "nothing dropped silently")
     S1 rule = highest sourceRevision of the CONTIGUOUS all-success prefix (stop at first failure)
               → failed items stay > watermark and are re-fetched next cycle.
     S2 (when ErrorItem queue lands) = advance fully + enqueue failures for independent retry.
6. write SyncRun{read, written, failed, duration, expectedDelta}
7. anomaly check (design-in, alertable — B4 §6b / C2 thresholds):
     - written==0 && expectedDelta>0  → anomalyFlags += zero_change    // "success, wrote nothing" (relies on §2 independence)
     - consecutive failed runs >= N   → anomalyFlags += consecutive_failure
   (checksum_mismatch reconcile = on-demand, S2)
```
**Note (B4#3, confirm at R1 spike):** `watermark - overlapLookback` assumes contiguous per-project revisions. If Polarion revisions are sparse/global, redefine `sinceRev` as "the K-th prior synced revision" or a **time-based lookback** — not integer subtraction. Verified against real Polarion at the R1 sandbox spike; mock uses the contiguous model.

`[REQ-M1-02-AC1]`: run #1 loads all; change 5 items; run #2 fetches only those 5 (delta via watermark); SyncRun health record written both runs. ✅ testable vs mock. TS-B-03 asserts the §2 probe-independence and the §5 no-skip-on-failure invariants (B4-reviewed).

## 4. Data touchpoints (B2 v1.2 data model — schema Flyway V1 lands at G3)
`TestCase` / `Requirement` / `Defect` (synced; + `contentHash char(64)`, `sourceRevision`, `lastSyncedAt`) · `SyncState(projectId, watermark)` · `SyncRun(...health...)`. No new entities beyond B2 v1.2.

## 5. Security (C4)
- M1-T1: Polarion token from secret store only (`POLARION_TOKEN` via `secretRef`) — never in code/config/repo.
- M1-T2: TLS to Polarion (`https://` in sandbox/live; mock is in-cluster).
- Sync-config mutations already RBAC deny-by-default + audited (PR#2).

## 6. Test hooks (C1 #7 / TS-B)
- Contract test → mock Polarion + internal Sync API (6 endpoints), shape = B2 v1.2.
- `[REQ-M1-02-AC1]` / TS-B-01 → delta-only + health record.
- TS-B-03 (silent-sync) fixtures target the **independent delta probe** (§3 step 2) — S2, but the probe is designed-in now.
- On implementation, I ping @VSDEQATestAgent with `APP_BASE_URL` so `@requires_app` stubs flip active.

## 7. On G3 sign-off — implementation order (est. this-session once unblocked)
1. Flyway V1 schema (B2 v1.2 entities) → 2. `PolarionClient` + `MockPolarionClient` → 3. `AntiCorruptionMapper` → 4. `SyncWorker` read-sync + watermark + SyncRun → 5. `POST /run` + `GET /status` → 6. unit + contract tests (TS-B-01) → PR#3 for Senior-Dev review.

**Blocked on:** 🔴 #5 G3 freeze (Jacky) — contract/data-model must be frozen before this is implemented (no bypass). 🟡 repo/CI remote for real PR/CI.
