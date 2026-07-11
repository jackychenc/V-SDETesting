# PR#1 `feat/scaffold` — Build note (B3, Sprint-1)

**Task:** #6 · **Owner:** B3 (agent draft) → **Senior Developer** (Jacky) reviews & merges · **Status:** scaffold foundation, pre-G3.
**Baselines:** B1 requirements v1.0 (G1 signed) · B2 IF-1 contract + data model v1.1 (**pending Lead Architect G3**) · C4 threat model (task #8).

## What changed + IDs (for C3 doc-drift / KT sync)
| Area | Files | ReqID / ref |
|---|---|---|
| Repo + build | `backend/build.gradle`, `frontend/package.json`, `.gitignore`, `docker-compose.yml` | stack React+Spring+PostgreSQL (G3-pending) |
| App bootstrap | `TmsApplication.java`, `application.yml` | modular monolith + scheduling (REQ-M1-02 15-min) |
| Security foundation | `config/SecurityConfig.java` | REQ-M5-01 RBAC deny-by-default (C4 M5-T2: server-side central); REQ-M5-02 SSO stub (OUT-01) |
| No-secrets posture | `.gitignore`, `application.yml` (env), `k8s/*` (secretRef) | NFR-SEC-01; C4 M1-T1 creds in secret store |
| i18n | `frontend/src/i18n/{index.ts,en.json,zh.json}` | REQ-M4-06 bilingual EN/中文 (TS-B-07) |
| Deploy | `k8s/deployment.yaml`, `k8s/service-and-config.yaml` | NFR-DEPLOY-01; health probes NFR-AVAIL-01; TS-B-09 |
| CI | `ci/.gitlab-ci.yml` | stages lint→unit→api→e2e→perf→**security(C4 #8)**→rtm(C1 #7) |
| Test harness seed | `SecurityConfigTest.java` | REQ-M5-01 (unit); TS-B-06 negative path deferred to PR#2 |

## Explicitly deferred (gated) — NOT in this PR
- **DB schema (Flyway V1)** = B2's frozen 18-entity data model → **after G3**. `db/migration` is empty by design.
- **M1 read-sync** (REQ-M1-01/02) + internal Sync API (6 endpoints) + reconcile/read-model (B4 spec a30ab3fe) → **after G3**, built vs mock Polarion (OUT-02).
- **M5 RBAC endpoint role-matrix + audit hook** (REQ-M5-03) → PR#2, per C4 threat model.

## Applied threat-model mitigations (C4 task #8)
- M5-T2 deny-by-default, server-side, central filter ✅ (SecurityConfig)
- M1-T1 no Polarion creds in scaffold — secret store from day 1 ✅
- M1-T2 TLS-to-Polarion assumed at ingress; M5-T3/T4 audit hooks scaffolded for PR#2

## Next
PR#2 `feat/m5-rbac-skeleton` (post C4 threat model — done) · PR#3 `feat/m1-read-sync` (post G3 sign-off).
