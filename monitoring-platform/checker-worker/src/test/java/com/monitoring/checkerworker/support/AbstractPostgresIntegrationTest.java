package com.monitoring.checkerworker.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Без Docker интеграционные тесты ПРОПУСКАЮТСЯ, а не падают: unit-тесты
 * по-прежнему можно гонять где угодно.
 *
 * Исключение — переменная окружения REQUIRE_DOCKER=true (её выставляет CI):
 * там отсутствие Docker означает сломанное окружение, и молчаливый пропуск
 * тестов дал бы зелёную сборку, которая ничего не проверила.
 */
@SpringJUnitConfig(IntegrationTestConfig.class)
// Значения указаны явно, чтобы тесты не зависели от изменения значений по умолчанию
@TestPropertySource(properties = {
    "checker.failure-threshold=3",
    "checker.retry-interval-seconds=15"
})
public abstract class AbstractPostgresIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeAll
    public static void startDatabase() {
        boolean dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        if (!dockerAvailable && "true".equals(System.getenv("REQUIRE_DOCKER"))) {
            throw new IllegalStateException("REQUIRE_DOCKER=true, но Docker недоступен");
        }
        assumeTrue(dockerAvailable, "Docker недоступен: интеграционные тесты пропущены");
        PostgresTestDatabase.start();
    }

    @BeforeEach
    protected void cleanTables() {
        jdbc.execute("TRUNCATE outbox_event, check_result, monitor, app_user CASCADE");
    }
}
