# PR#2 `feat/m5-rbac-skeleton` — Build note (B3, Sprint-1)

**Task:** #6 · **Stacked on:** PR#1 `feat/scaffold` · **Owner:** B3 → Senior Developer review · **Status:** RBAC skeleton (non-gated authz layer).
**Baselines:** B1 v1.0 (REQ-M5-01/02/03/04) · C4 threat model task #8 (M5-T2/T3/T4).

## What changed + IDs (for C3 doc-drift / KT sync)
| File | Delivers | ReqID / threat |
|---|---|---|
| `config/SecurityConfig.java` | endpoint→role authorization matrix, **deny-by-default** (`anyRequest().denyAll()`), 4 roles | REQ-M5-01 / M5-T2; AC [REQ-M5-01-AC1] |
| `m5admin/AuditEvent.java` | audit record (actor/action/resource/outcome/UTC) incl. **DENY** events | REQ-M5-03 / NFR-DATA-01 |
| `m5admin/AuditService.java` | audit sink interface + logging impl (JPA impl post-G3) | REQ-M5-03 / M5-T3/T4 |
| `m5admin/AuthorizationDeniedAuditListener.java` | audits **every authorization denial** (not just HTTP 403) | REQ-M5-03; TS-B-06 |
| `m5admin/SyncConfigController.java` | admin sync-config endpoint (deny-by-default + audit on mutating PUT) | REQ-M5-04/01/03 |
| `test/…/RbacNegativeTest.java` | RBAC negative matrix: Admin allowed · Tester/Viewer denied+audited · anon 401 | REQ-M5-01; **TS-B-06** |

## Authorization matrix (deny-by-default)
| Path / method | Allowed roles |
|---|---|
| `/api/sync/config/**`, `/api/admin/**`, `/api/audit/**` | ADMINISTRATOR |
| `POST|PUT /api/runs/**`, `POST /api/sync/conflicts/**` | TEST_LEAD, ADMINISTRATOR |
| `POST /api/executions/**`, `/api/defects/**` | TESTER, TEST_LEAD, ADMINISTRATOR |
| `GET /api/**` | any authenticated |
| everything else | **denied** |

## Deferred (gated) — NOT in this PR
- Persisted `User`/`Role`/`ProjectRoleAssignment`/`AuditEvent` tables = B2 data model → **post-G3** (Flyway V1). Audit currently logs; JPA sink added with schema.
- SSO/LDAP real integration (REQ-M5-02) = pilot stub (OUT-01); prod ISSO IdP later.
- Per-project role scoping (deny-by-default is global now; project-scoped assignment lands with `ProjectRoleAssignment` post-G3).

## Applied C4 threat mitigations
M5-T2 deny-by-default central server-side ✅ · M5-T3/T4 audit on mutating ops + denials ✅.

## Test status
`RbacNegativeTest` (TS-B-06) is written; runs in CI once the VGC repo + runner are provisioned (🟡 blocker). Local: draft for Senior-Dev review.
