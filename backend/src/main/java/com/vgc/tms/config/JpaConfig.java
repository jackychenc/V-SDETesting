package com.vgc.tms.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA wiring for the full application context.
 *
 * The nested static @Entity classes ({@code SyncEntities.*}) and nested repository interfaces
 * ({@code SyncRepositories.*}) are registered explicitly here as managed types / repositories.
 *
 * This lives on a plain @Configuration (component-scanned by the full app) rather than on
 * TmsApplication ON PURPOSE: a @WebMvcTest slice processes @Enable* annotations found on the
 * @SpringBootApplication class, so @EnableJpaRepositories there would drag JPA repositories — and
 * hence an 'entityManagerFactory' — into a web-only slice, failing context load with
 * NoSuchBeanDefinitionException. @WebMvcTest does NOT load arbitrary @Configuration classes, so
 * keeping the JPA wiring here leaves RBAC/web slices clean while the full app is unaffected.
 */
@Configuration
@EntityScan("com.vgc.tms")
@EnableJpaRepositories("com.vgc.tms")
public class JpaConfig {
}
