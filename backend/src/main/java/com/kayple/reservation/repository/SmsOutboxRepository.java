package com.kayple.reservation.repository;

import com.kayple.reservation.domain.SmsEventType;
import com.kayple.reservation.domain.SmsOutbox;
import com.kayple.reservation.domain.SmsStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class SmsOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<SmsOutbox> ROW_MAPPER = (rs, rowNum) -> {
        SmsOutbox o = new SmsOutbox();
        o.setId(rs.getLong("id"));
        o.setReservationId(rs.getLong("reservation_id"));
        o.setEventType(SmsEventType.valueOf(rs.getString("event_type")));
        o.setPhone(rs.getString("phone"));
        o.setMessage(rs.getString("message"));
        o.setIdempotencyKey(rs.getString("idempotency_key"));
        o.setStatus(SmsStatus.valueOf(rs.getString("status")));
        o.setAttemptCount(rs.getInt("attempt_count"));
        Timestamp nextRetry = rs.getTimestamp("next_retry_at");
        if (nextRetry != null) {
            o.setNextRetryAt(nextRetry.toLocalDateTime());
        }
        Timestamp processingStarted = rs.getTimestamp("processing_started_at");
        if (processingStarted != null) {
            o.setProcessingStartedAt(processingStarted.toLocalDateTime());
        }
        Timestamp firstAttempt = rs.getTimestamp("first_attempt_at");
        if (firstAttempt != null) {
            o.setFirstAttemptAt(firstAttempt.toLocalDateTime());
        }
        o.setLastError(rs.getString("last_error"));
        o.setProviderMessageId(rs.getString("provider_message_id"));
        Timestamp created = rs.getTimestamp("created_at");
        if (created != null) {
            o.setCreatedAt(created.toLocalDateTime());
        }
        Timestamp sent = rs.getTimestamp("sent_at");
        if (sent != null) {
            o.setSentAt(sent.toLocalDateTime());
        }
        return o;
    };

    public SmsOutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SmsOutbox insert(SmsOutbox outbox) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                    INSERT INTO sms_outbox
                    (reservation_id, event_type, phone, message, idempotency_key, status, next_retry_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP)
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outbox.getReservationId());
            ps.setString(2, outbox.getEventType().name());
            ps.setString(3, outbox.getPhone());
            ps.setString(4, outbox.getMessage());
            ps.setString(5, outbox.getIdempotencyKey());
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            outbox.setId(key.longValue());
        }
        outbox.setStatus(SmsStatus.PENDING);
        return outbox;
    }

    /**
     * Must be called inside an open transaction. Claims rows atomically with FOR UPDATE SKIP LOCKED.
     */
    public List<Long> claimPending(int limit, int processingTimeoutSeconds) {
        jdbcTemplate.update(
                """
                UPDATE sms_outbox
                SET status = 'PENDING',
                    next_retry_at = CURRENT_TIMESTAMP,
                    processing_started_at = NULL,
                    last_error = LEFT(CONCAT(IFNULL(last_error, ''), ' | recovered stale PROCESSING'), 500)
                WHERE status = 'PROCESSING'
                  AND processing_started_at < (CURRENT_TIMESTAMP - INTERVAL ? SECOND)
                """,
                processingTimeoutSeconds);

        List<Long> ids = jdbcTemplate.queryForList(
                """
                SELECT id FROM sms_outbox
                WHERE status = 'PENDING' AND next_retry_at <= CURRENT_TIMESTAMP
                ORDER BY next_retry_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                Long.class,
                limit);

        if (!ids.isEmpty()) {
            String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
            jdbcTemplate.update(
                    "UPDATE sms_outbox SET status = 'PROCESSING', processing_started_at = CURRENT_TIMESTAMP WHERE id IN ("
                            + placeholders + ")",
                    ids.toArray());
        }
        return ids;
    }

    public Optional<SmsOutbox> findById(Long id) {
        List<SmsOutbox> list = jdbcTemplate.query(
                "SELECT * FROM sms_outbox WHERE id = ?",
                ROW_MAPPER,
                id);
        return list.stream().findFirst();
    }

    public void markSent(Long id, String providerMessageId) {
        jdbcTemplate.update(
                """
                UPDATE sms_outbox
                SET status = 'SENT',
                    provider_message_id = ?,
                    sent_at = CURRENT_TIMESTAMP,
                    processing_started_at = NULL,
                    last_error = NULL,
                    attempt_count = attempt_count + 1,
                    first_attempt_at = COALESCE(first_attempt_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """,
                providerMessageId,
                id);
    }

    public void markFailed(Long id, String error) {
        jdbcTemplate.update(
                """
                UPDATE sms_outbox
                SET status = 'FAILED',
                    last_error = ?,
                    processing_started_at = NULL,
                    attempt_count = attempt_count + 1,
                    first_attempt_at = COALESCE(first_attempt_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """,
                truncate(error),
                id);
    }

    public void scheduleRetry(Long id, LocalDateTime nextRetryAt, String error) {
        jdbcTemplate.update(
                """
                UPDATE sms_outbox
                SET status = 'PENDING',
                    next_retry_at = ?,
                    last_error = ?,
                    processing_started_at = NULL,
                    attempt_count = attempt_count + 1,
                    first_attempt_at = COALESCE(first_attempt_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """,
                Timestamp.valueOf(nextRetryAt),
                truncate(error),
                id);
    }

    public Map<Long, Map<String, String>> findStatusesByReservationIds(List<Long> reservationIds) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        if (reservationIds == null || reservationIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", reservationIds.stream().map(id -> "?").toList());
        jdbcTemplate.query(
                "SELECT reservation_id, event_type, status FROM sms_outbox WHERE reservation_id IN ("
                        + placeholders + ")",
                rs -> {
                    Long reservationId = rs.getLong("reservation_id");
                    result.computeIfAbsent(reservationId, k -> new HashMap<>())
                            .put(rs.getString("event_type"), rs.getString("status"));
                },
                reservationIds.toArray());
        return result;
    }

    public long countByReservationAndEvent(Long reservationId, SmsEventType eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sms_outbox WHERE reservation_id = ? AND event_type = ?",
                Long.class,
                reservationId,
                eventType.name());
        return count == null ? 0 : count;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }
}
