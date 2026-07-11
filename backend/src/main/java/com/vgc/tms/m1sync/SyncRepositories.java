package com.vgc.tms.m1sync;

import com.vgc.tms.m1sync.SyncEntities.*;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repositories for the M1 read-sync entities. */
public final class SyncRepositories {
    private SyncRepositories() {}

    public interface TestCaseRepository extends JpaRepository<TestCaseEntity, Long> {
        Optional<TestCaseEntity> findByProjectIdAndPolarionId(Long projectId, String polarionId);
        List<TestCaseEntity> findByProjectId(Long projectId);   // reverse-missing (REQ-M1-05)
        long countByProjectId(Long projectId);                  // Tier-2 row-count tripwire
    }

    public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> { }

    public interface SyncStateRepository extends JpaRepository<SyncStateEntity, Long> { }

    public interface SyncRunRepository extends JpaRepository<SyncRunEntity, Long> {
        SyncRunEntity findFirstByProjectIdOrderByStartedAtDesc(Long projectId);
    }
}
