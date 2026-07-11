# TMS Enhancement — Test Execution Dashboard & Polarion Sync (FSR-B pilot)

VGC C-SDE Basic-layer pilot. Web-based Test Management System with bidirectional Polarion sync,
test execution + evidence, defect linkage, real-time dashboards, RBAC/audit. Bilingual EN/中文.

- **Owner (build):** B3 Application Development (agent draft → **Senior Developer** reviews & merges; agent never merges).
- **Baselines:** requirements = B1 v1.0 (G1 signed, ReqID REQ-M1-01…REQ-M5-04); architecture = B2 IF-1 contract + data model v1.1 (**pending Lead Architect G3**).
- **Status:** Sprint-1 scaffold (PR#1). M1 read-sync + concrete DB schema are **gated on G3 contract freeze**; this PR is the non-gated foundation.

## Stack (pending Jacky/Lead-Architect G3 confirm)
| Layer | Tech |
|---|---|
| Frontend | React + TypeScript + Vite, i18n (EN/中文), VW UI style |
| Backend | Java 21 + Spring Boot 3 (modular monolith + isolated Sync Worker) |
| Data | PostgreSQL 16 (+ JSONB for sync-config), Flyway migrations, object store for evidence |
| Deploy | Docker + Kubernetes manifests, config via env/secret store |

## Modules (→ ReqIDs)
| Pkg | Module | ReqIDs | Sprint |
|---|---|---|---|
| `m1sync` | Polarion Sync (Sync Worker, ACL, conflict queue, reconciler) | REQ-M1-01..06 | S1 read-sync → S2 bidi |
| `m2execution` | Test Execution (runs, executions, evidence) | REQ-M2-01..04 | S2 |
| `m3defect` | Defect Linkage & Traceability | REQ-M3-01..03 | S2 |
| `m4dashboard` | Dashboard & Reports (read models, exports, bilingual) | REQ-M4-01..06 | S3 |
| `m5admin` | Admin & Access (RBAC deny-by-default, audit, sync-config) | REQ-M5-01..04 | S1 skeleton → S2/S3 |

## Layout
```
backend/    Spring Boot (com.vgc.tms.<module>)
frontend/   React + TS + i18n
k8s/        Kubernetes manifests (skeleton)
ci/         CI pipeline (lint→unit→api→e2e→perf→security→rtm)
docs/       scaffold + build notes
```

## Local dev
```
docker compose up -d db        # PostgreSQL
cd backend && ./gradlew bootRun
cd frontend && npm i && npm run dev
```

## Gates (every deliverable human-signed)
Lead Architect (G3 design) · Senior Developer (PR merge) · Quality Manager (G4 test) · Compliance Manager (G5 security).
No secrets in repo. Feature-branch PRs only.
