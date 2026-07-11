package com.vgc.tms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TMS Enhancement — Test Execution Dashboard & Polarion Sync (FSR-B pilot).
 * Modular monolith: modules m1sync / m2execution / m3defect / m4dashboard / m5admin.
 * Scheduling enabled for the incremental Polarion sync (REQ-M1-02, default 15 min).
 *
 * NOTE: explicit @EntityScan/@EnableJpaRepositories live on {@link com.vgc.tms.config.JpaConfig}
 * (a plain @Configuration), NOT here. Putting them on the @SpringBootApplication class makes a
 * @WebMvcTest slice (e.g. RbacNegativeTest) process @EnableJpaRepositories → it pulls in JPA repos
 * → requires an 'entityManagerFactory' the web slice has no reason to bootstrap → context-load fails
 * with NoSuchBeanDefinitionException. A separate @Configuration is component-scanned by the full app
 * but is NOT loaded by @WebMvcTest (which excludes plain @Configuration), so the slice stays JPA-free.
 */
@SpringBootApplication
@EnableScheduling
public class TmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(TmsApplication.class, args);
    }
}
