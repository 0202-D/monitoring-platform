package com.monitoring.checkerworker.support;

import com.monitoring.checkerworker.repository.MonitorJdbcRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

/**
 * Минимальный Spring-контекст: именно контекст (а не голый new), потому что
 * поведение MonitorJdbcRepository зависит от @Transactional. Без прокси
 * SELECT ... FOR UPDATE выполнялся бы в autocommit и блокировка снималась бы
 * сразу после запроса — тест проверял бы не то, что работает в приложении.
 */
@Configuration
@EnableTransactionManagement
public class IntegrationTestConfig {

    @Bean
    public DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
            PostgresTestDatabase.jdbcUrl(),
            PostgresTestDatabase.username(),
            PostgresTestDatabase.password());
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public MonitorJdbcRepository monitorJdbcRepository(JdbcTemplate jdbcTemplate) {
        return new MonitorJdbcRepository(jdbcTemplate);
    }
}
