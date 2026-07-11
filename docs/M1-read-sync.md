# PR#3 `feat/m1-read-sync` — Build note (B3, Sprint-1)

**Task:** #6 · **Gate:** post-**G3** (B2 contract v1.2 frozen, 36c3635a) · **Owner:** B3 → **Senior Developer (Jacky, interim)** review · **Status:** M1 read-sync (Polarion→TMS) implemented.
**Baselines:** B1 v1.0 (REQ-M1-01/02/05/06) · B2 IF-1 contract + data model v1.2 · B4 build spec a30ab3fe + 2 correctness guards · C4 threat model (M1-T1/T2).

## What changed + IDs (for C3 doc-drift / KT sync)
| File | Delivers | ReqID |
|---|---|---|
| `db/migration/V1__init.sql` | **full frozen 18-entity schema** (M1–M5) + read-model tables + indexes + role seed | B2 data model v1.2; NFR-PERF-01/DATA-01 |
| `m1sync/PolarionClient.java` + `PolarionWorkItem.java` | IF-1 read interface (independent `countChangedSince` + `fetchChangedSince`) + ACL DTO | REQ-M1-01/02 |
| `m1sync/MockPolarionClient.java` | in-memory mock (OUT-02), contract-identical, `@Profile("mock")` | REQ-M1-01 |
| `common/ContentHash.java` | SHA-256 fixed-key-order field-set hash | B4 (drift/reconcile) |
| `m1sync/SyncEntities.java` + `SyncRepositories.java` | JPA: Project, TestCase (synced), SyncState (watermark), SyncRun (health) + repos | REQ-M1-01/02/05 |
| `m1sync/ReadSyncService.java` | **incremental read-sync algorithm** — independent probe, idempotent upsert on polarionId, contiguous-success watermark, anomaly flags | REQ-M1-02/05/06 |
| `m1sync/SyncController.java` | internal Sync API: `POST /{proj}/run` (admin/lead, RBAC), `GET /{proj}/status` | REQ-M1-02/05 |
| `m1sync/SyncScheduler.java` | scheduled sync (default 15 min, per-project isolated) | REQ-M1-02 |
| `test/…/ReadSyncServiceTest.java` | TS-B-01 / [REQ-M1-02-AC1] + B4#1 zero_change + B4#2 no-skip watermark | REQ-M1-02/06; TS-B-01/03 seed |

## B4 correctness guards implemented (verified by tests)
- **#1 independent delta probe** — `countChangedSince` is a separate query from `fetchChangedSince`; `zero_change` fires when `written==0 && expectedDelta>0` (silent-sync-failure detectable — TS-B-03). Test: `silentFailure_zeroChangeAnomaly_viaIndependentProbe`.
- **#2 watermark ≤ contiguous all-success prefix** — failed items stay above the watermark and re-fetch next cycle (REQ-M1-06, no silent drop). Test: `watermarkStopsAtContiguousSuccessPrefix_onFailure`.
- **#3 overlapLookback** — integer contiguous model in mock; **confirm real Polarion revision model at the R1 sandbox spike** (may switch to K-th-prior / time-based).

## Security (C4)
M1-T1 Polarion token from secret store only (`POLARION_TOKEN` secretRef) — none in code/config/repo ✅ · M1-T2 TLS to Polarion (REST impl; mock in-cluster).

## Scope boundary (Sprint-2, not this PR)
Bidirectional write-back (REQ-M1-03 per-field direction), conflict queue (REQ-M1-04), reconcile endpoint + checksum (REQ-M1-05 on-demand), error-queue retry (REQ-M1-06 full) — and defect write-back with the **M1-T5 field allow-list** (C4). Requirement/Defect synced entities use the same upsert pattern as TestCase.

## Handoff
@VSDEQATestAgent (#7) — `MockPolarionClient` + the 2 Sync-API endpoints are your contract-test + TS-B-01/03 target; app is buildable with `mock` profile. Ping-ready once repo/CI runner is up.
@VSDEDataEngineeringAgent — please spot-review `ReadSyncService` watermark + probe-independence per your offer.
