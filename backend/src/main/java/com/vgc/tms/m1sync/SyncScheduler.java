package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.ProjectEntity;
import com.vgc.tms.m1sync.SyncRepositories.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled incremental read-sync (REQ-M1-02) — default 15 min, configurable via
 * {@code tms.polarion.sync.interval}. Enumerates mapped projects and runs each incrementally.
 * On-demand sync is via {@link SyncController} POST /run. Failures in one project don't stop others.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);
    private final ProjectRepository projects;
    private final ReadSyncService readSync;

    public SyncScheduler(ProjectRepository projects, ReadSyncService readSync) {
        this.projects = projects;
        this.readSync = readSync;
    }

    @Scheduled(fixedDelayString = "${tms.polarion.sync.interval:PT15M}")
    public void scheduledSync() {
        for (ProjectEntity p : projects.findAll()) {
            try {
                var run = readSync.sync(p.id, p.polarionProjectId);
                if (!run.anomalyFlags.isBlank()) {
                    log.warn("sync anomaly project={} flags={} run={}", p.tmsProjectId, run.anomalyFlags, run.id);
                }
            } catch (RuntimeException ex) {
                log.error("scheduled sync failed for project={}", p.tmsProjectId, ex);  // isolate per-project
            }
        }
    }
}
