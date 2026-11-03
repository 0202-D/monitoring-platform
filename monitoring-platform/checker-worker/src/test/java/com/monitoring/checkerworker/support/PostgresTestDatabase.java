package com.monitoring.checkerworker.support;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * Один контейнер Postgres на весь прогон тестов (паттерн singleton container):
 * запуск контейнера занимает секунды, поэтому делать его на каждый тестовый
 * класс было бы расточительно. Контейнер останавливает Ryuk при завершении JVM.
 *
 * Схема создаётся ТЕМИ ЖЕ миграциями Liquibase, что и в core-api: копия схемы
 * в тестовых ресурсах рано или поздно разошлась бы с настоящей.
 *
 * Примечание про Docker: Testcontainers старше 1.21.4 не работают с Docker 29+
 * (он поднял минимальную версию API до 1.44). Версия из BOM Spring Boot 3.3
 * подходит для Docker Desktop 4.x с движком 27/28; при переходе на Docker 29
 * поднимите Testcontainers до 1.21.4 и выше.
 */
public final class PostgresTestDatabase {

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {
    }

    public static synchronized void start() {
        if (container != null) {
            return;
        }
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        migrate(postgres);
        container = postgres;
    }

    public static String jdbcUrl() {
        return container.getJdbcUrl();
    }

    public static String username() {
        return container.getUsername();
    }

    public static String password() {
        return container.getPassword();
    }

    private static void migrate(PostgreSQLContainer<?> postgres) {
        Path changelogRoot = findChangelogRoot();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new DirectoryResourceAccessor(changelogRoot),
                    database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось применить миграции core-api к тестовой БД", e);
        }
    }

    /**
     * Maven запускает тесты с рабочим каталогом модуля (checker-worker), IDE
     * иногда запускает из корня проекта, поэтому проверяем оба расположения.
     */
    private static Path findChangelogRoot() {
        List<Path> candidates = List.of(
            Path.of("..", "core-api", "src", "main", "resources"),
            Path.of("core-api", "src", "main", "resources"));
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate.resolve("db").resolve("changelog"))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
            "Не найдены миграции core-api (core-api/src/main/resources/db/changelog). "
                + "Запускайте тесты из корня проекта или из каталога модуля checker-worker.");
    }
}
