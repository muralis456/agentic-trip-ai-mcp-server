package com.example.travel.mcp.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit Flyway configuration for the MCP server.
 *
 * <p>The MCP governance entities use {@code ddl-auto=validate}, so the database
 * schema must be migrated before Hibernate creates the EntityManagerFactory.
 * Defining Flyway and its migration initializer explicitly also makes startup
 * migration independent of conditional Flyway auto-configuration.</p>
 */
@Configuration
public class FlywayMigrationConfig {

    @Bean(name = "flyway")
    Flyway flyway(DataSource dataSource) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .validateOnMigrate(true)
                .cleanDisabled(true);

        return configuration.load();
    }

    @Bean(name = "flywayInitializer")
    FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
        return new FlywayMigrationInitializer(flyway);
    }
}
