package com.monitoring.notificationservice.support;

import com.monitoring.notificationservice.channel.UserChannelRepository;
import com.monitoring.notificationservice.delivery.DeliveryRepository;
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
 * DeliveryRepository.claimDue зависит от @Transactional. Без прокси SELECT ... FOR UPDATE
 * выполнялся бы в autocommit, блокировка снималась бы сразу после запроса, и тест
 * проверял бы не то, что работает в приложении.
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
    public DeliveryRepository deliveryRepository(JdbcTemplate jdbcTemplate) {
        return new DeliveryRepository(jdbcTemplate);
    }

    @Bean
    public UserChannelRepository userChannelRepository(JdbcTemplate jdbcTemplate) {
        return new UserChannelRepository(jdbcTemplate);
    }
}
