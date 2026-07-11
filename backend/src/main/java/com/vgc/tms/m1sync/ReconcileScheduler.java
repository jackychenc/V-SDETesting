package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.ProjectEntity;
import com.vgc.tms.m1sync.SyncRepositories.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled reconciliation (contract v1.3 §E3) — makes the drift-detection SLA real.
 * SLA-ON by non-null defaults (overridable per-project via versioned SyncConfig, REQ-M5-04):
 *  - Tier-3 full-hash scan: {@code reconcile.fullScan.cron} default daily@off-peak → already-synced drift ≤24h.
 *  - Tier-2 row-count tripwire: {@code reconcile.rowCount.interval} default 1h → gross drift ≤1h.
 * Per-project isolated; anomalies logged as alertable signals (C2 thresholds).
 */
@Component
public class ReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconcileScheduler.class);
    private final ProjectRepository projects;
    private final ReconcileService reconcile;

    public ReconcileScheduler(ProjectRepository projects, ReconcileService reconcile) {
        this.projects = projects;
        this.reconcile = reconcile;
    }

    /** Tier-3 nightly full-hash scan (≤24h SLA). Default 02:30 daily (off-peak). */
    @Scheduled(cron = "${reconcile.fullScan.cron:0 30 2 * * *}")
    public void fullScan() {
        for (ProjectEntity p : projects.findAll()) {
            try {
                var r = reconcile.reconcile(p.id, p.polarionProjectId);
                if (!r.clean()) {
                    log.warn("reconcile drift project={} mismatched={} missingInTMS={} missingInPolarion={}",
                            p.tmsProjectId, r.mismatched(), r.missingInTMS().size(), r.missingInPolarion().size());
                }
            } catch (RuntimeException ex) {
                log.error("full-scan reconcile failed project={}", p.tmsProjectId, ex);
            }
        }
    }

    /** Tier-2 hourly row-count tripwire (≤1h SLA), count-only. */
    @Scheduled(fixedDelayString = "${reconcile.rowCount.interval:PT1H}")
    public void rowCountTripwire() {
        for (ProjectEntity p : projects.findAll()) {
            try {
                var r = reconcile.rowCountTripwire(p.id, p.polarionProjectId);
                if (r.tripped()) {
                    log.warn("reconcile row-count tripwire project={} polarion={} tms={} drift={}",
                            p.tmsProjectId, r.polarionCount(), r.tmsCount(), r.drift());
                }
            } catch (RuntimeException ex) {
                log.error("row-count tripwire failed project={}", p.tmsProjectId, ex);
            }
        }
    }
}
