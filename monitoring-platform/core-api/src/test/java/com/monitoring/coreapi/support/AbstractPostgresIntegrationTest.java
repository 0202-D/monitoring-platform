package com.monitoring.coreapi.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Без Docker интеграционные тесты ПРОПУСКАЮТСЯ, а не падают. Исключение: переменная
 * окружения REQUIRE_DOCKER=true (её выставляет CI): там отсутствие Docker означает сломанное
 * окружение, и молчаливый пропуск дал бы зелёную сборку, которая ничего не проверила.
 *
 * Spring-контекст здесь не нужен: проверяемая логика использует только JdbcTemplate.
 */
public abstract class AbstractPostgresIntegrationTest {

    protected static JdbcTemplate jdbc;

    @BeforeAll
    public static void startDatabase() {
        boolean dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable && "true".equals(System.getenv("REQUIRE_DOCKER"))) {
            throw new IllegalStateException("REQUIRE_DOCKER=true, но Docker недоступен");
        }
        assumeTrue(dockerAvailable, "Docker недоступен: интеграционные тесты пропущены");
        PostgresTestDatabase.start();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            PostgresTestDatabase.jdbcUrl(), PostgresTestDatabase.username(), PostgresTestDatabase.password());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    public void cleanTables() {
        jdbc.execute("TRUNCATE check_result, outbox_event, monitor, app_user CASCADE");
    }
}
