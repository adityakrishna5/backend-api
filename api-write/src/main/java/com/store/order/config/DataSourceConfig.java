package com.store.order.config;

import org.springframework.context.annotation.Configuration;

/**
 * DataSource is auto-configured from application.yml spring.datasource settings.
 * HikariCP pool sizing is set via spring.datasource.hikari.* properties.
 * Cloud SQL Auth Proxy sidecar handles IAM auth transparently on GCP.
 */
@Configuration
public class DataSourceConfig {
}
