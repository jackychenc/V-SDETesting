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
    }

    public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> { }

    public interface SyncStateRepository extends JpaRepository<SyncStateEntity, Long> { }

    public interface SyncRunRepository extends JpaRepository<SyncRunEntity, Long> {
        SyncRunEntity findFirstByProjectIdOrderByStartedAtDesc(Long projectId);
    }

    public interface ConflictItemRepository extends JpaRepository<ConflictItemEntity, Long> {
        List<ConflictItemEntity> findByItemPolarionIdAndStatus(String itemPolarionId, String status);
        List<ConflictItemEntity> findByStatus(String status);
    }

    public interface ErrorItemRepository extends JpaRepository<ErrorItemEntity, Long> {
        List<ErrorItemEntity> findByStatusIn(List<String> statuses);
    }
}
