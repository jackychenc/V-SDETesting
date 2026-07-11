package com.vgc.tms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TMS Enhancement — Test Execution Dashboard & Polarion Sync (FSR-B pilot).
 * Modular monolith: modules m1sync / m2execution / m3defect / m4dashboard / m5admin.
 * Scheduling enabled for the incremental Polarion sync (REQ-M1-02, default 15 min).
 *
 * Explicit @EntityScan/@EnableJpaRepositories so the nested static @Entity classes
 * (SyncEntities.*) and nested repository interfaces (SyncRepositories.*) are registered
 * as managed types / repositories (avoids "Not a managed type" on context load).
 */
@SpringBootApplication
@EnableScheduling
@EntityScan("com.vgc.tms")
@EnableJpaRepositories("com.vgc.tms")
public class TmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(TmsApplication.class, args);
    }
}
