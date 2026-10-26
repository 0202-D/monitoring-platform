package com.monitoring.notificationservice.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public ProcessedEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean exists(String eventId, String channelType) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM processed_event WHERE event_id = ? AND channel_type = ?",
            Integer.class, eventId, channelType);
        return count != null && count > 0;
    }

    /** ON CONFLICT DO NOTHING: повторная запись той же пары безвредна. */
    public void markProcessed(String eventId, String channelType) {
        jdbcTemplate.update(
            "INSERT INTO processed_event (event_id, channel_type) VALUES (?, ?) ON CONFLICT DO NOTHING",
            eventId, channelType);
    }
}
