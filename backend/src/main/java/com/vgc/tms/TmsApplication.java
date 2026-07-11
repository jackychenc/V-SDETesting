package com.vgc.tms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TMS Enhancement — Test Execution Dashboard & Polarion Sync (FSR-B pilot).
 * Modular monolith: modules m1sync / m2execution / m3defect / m4dashboard / m5admin.
 * Scheduling enabled for the incremental Polarion sync (REQ-M1-02, default 15 min).
 *
 * Scaffold PR#1 — foundation only. M1 sync logic + DB schema land post Lead-Architect G3.
 */
@SpringBootApplication
@EnableScheduling
public class TmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(TmsApplication.class, args);
    }
}
