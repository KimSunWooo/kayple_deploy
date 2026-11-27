package com.kayple.reservation;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.ReservationStatus;
import com.kayple.reservation.domain.SmsEventType;
import com.kayple.reservation.domain.SmsOutbox;
import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.repository.SmsOutboxRepository;
import com.kayple.reservation.service.ReservationService;
import com.kayple.reservation.support.IntegrationTestProperties;
import com.kayple.reservation.support.MySQLTestcontainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class OutboxIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        Assumptions.assumeTrue(MySQLTestcontainer.isAvailable(), "Docker unavailable");
        IntegrationTestProperties.registerCommon(registry);
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(MySQLTestcontainer.isAvailable(), "Docker unavailable");
    }

    @Autowired
    ReservationService reservationService;
    @SpyBean
    SmsOutboxRepository smsOutboxRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void resetSpy() {
        Mockito.reset(smsOutboxRepository);
    }

    @Test
    void createPersistsBookedOutboxInSameTransaction() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(3);
        Reservation created = reservationService.create(req("출박스일", "01070001111", date, 10));

        assertThat(smsOutboxRepository.countByReservationAndEvent(created.getId(), SmsEventType.BOOKED))
                .isEqualTo(1);
        Integer joined = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM reservations r
                JOIN sms_outbox o ON o.reservation_id = r.id AND o.event_type = 'BOOKED'
                WHERE r.id = ?
                """,
                Integer.class,
                created.getId());
        assertThat(joined).isEqualTo(1);
    }

    @Test
    void cancelPersistsCancelledOutboxOnce() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(3);
        Reservation created = reservationService.create(req("출박스이", "01070002222", date, 11));
        reservationService.cancel(created.getId(), "01070002222");

        assertThat(smsOutboxRepository.countByReservationAndEvent(created.getId(), SmsEventType.CANCELLED))
                .isEqualTo(1);
        assertThatThrownBy(() -> reservationService.cancel(created.getId(), "01070002222"));
        assertThat(smsOutboxRepository.countByReservationAndEvent(created.getId(), SmsEventType.CANCELLED))
                .isEqualTo(1);
    }

    @Test
    void outboxInsertFailureRollsBackReservationCreate() {
        doThrow(new DataIntegrityViolationException("forced outbox failure"))
                .when(smsOutboxRepository).insert(any(SmsOutbox.class));

        LocalDate date = LocalDate.now(SEOUL).plusDays(3);
        assertThatThrownBy(() -> reservationService.create(req("롤백일", "01070003333", date, 13)))
                .isInstanceOf(RuntimeException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE phone = '01070003333'",
                Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void cancelOutboxInsertFailureRollsBackCancel() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(3);
        Reservation created = reservationService.create(req("롤백이", "01070004444", date, 14));

        doThrow(new DataIntegrityViolationException("forced cancel outbox failure"))
                .when(smsOutboxRepository).insert(any(SmsOutbox.class));

        assertThatThrownBy(() -> reservationService.cancel(created.getId(), "01070004444"))
                .isInstanceOf(RuntimeException.class);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?",
                String.class,
                created.getId());
        assertThat(status).isEqualTo(ReservationStatus.CONFIRMED.name());
        assertThat(smsOutboxRepository.countByReservationAndEvent(created.getId(), SmsEventType.CANCELLED))
                .isZero();
    }

    @Test
    void idempotencyKeyIsUnique() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(3);
        Reservation created = reservationService.create(req("유니크키", "01070005555", date, 15));
        String key = jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM sms_outbox WHERE reservation_id = ? AND event_type = 'BOOKED'",
                String.class,
                created.getId());

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO sms_outbox
                (reservation_id, event_type, phone, message, idempotency_key, status, next_retry_at)
                VALUES (?, 'CANCELLED', '01070005555', 'dup', ?, 'PENDING', CURRENT_TIMESTAMP)
                """,
                created.getId(),
                key))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void schemaReinitDoesNotWipeExistingRows() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(3);
        Reservation created = reservationService.create(req("스키마유지", "01070006666", date, 16));

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS reservations (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    customer_name VARCHAR(100) NOT NULL,
                    phone VARCHAR(20) NOT NULL,
                    reservation_date DATE NOT NULL,
                    reservation_hour INT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    active_slot TINYINT
                        GENERATED ALWAYS AS (CASE WHEN status = 'CONFIRMED' THEN 1 ELSE NULL END) STORED,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    cancelled_at TIMESTAMP NULL,
                    CONSTRAINT uk_active_slot UNIQUE (reservation_date, reservation_hour, active_slot)
                )
                """);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE id = ?",
                Integer.class,
                created.getId());
        assertThat(count).isEqualTo(1);
    }

    private static CreateReservationRequest req(String name, String phone, LocalDate date, int hour) {
        CreateReservationRequest r = new CreateReservationRequest();
        r.setName(name);
        r.setPhone(phone);
        r.setDate(date);
        r.setHour(hour);
        return r;
    }
}
