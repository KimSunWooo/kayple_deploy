package com.kayple.reservation;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.ReservationStatus;
import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.exception.ApiException;
import com.kayple.reservation.service.ReservationService;
import com.kayple.reservation.support.IntegrationTestProperties;
import com.kayple.reservation.support.MySQLTestcontainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ReservationIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String[] NAMES = {
            "가나다", "라마바", "사아자", "차카타", "파하호", "에이비", "시디이", "에프지"
    };

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        Assumptions.assumeTrue(MySQLTestcontainer.isAvailable(), "Docker unavailable");
        IntegrationTestProperties.registerCommon(registry);
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(MySQLTestcontainer.isAvailable(), "Docker unavailable — skipping MySQL integration tests");
    }

    @LocalServerPort
    int port;

    @Autowired
    ReservationService reservationService;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void createsReservationAndPersistsConfirmedRow() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(1);
        CreateReservationRequest req = request("홍길동", "01011112221", date, 10);
        Reservation created = reservationService.create(req);

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(created.getPhone()).isEqualTo("01011112221");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE id = ? AND status = 'CONFIRMED'",
                Integer.class,
                created.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void duplicateSlotIsBlockedAtDatabaseAndHttp409() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(1);
        reservationService.create(request("김철수", "01011112222", date, 11));

        assertThatThrownBy(() -> reservationService.create(request("이영희", "01011112223", date, 11)))
                .isInstanceOf(DuplicateKeyException.class);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                url("/api/reservations"),
                jsonBody("""
                        {"name":"박민수","phone":"01011112224","date":"%s","hour":11}
                        """.formatted(date)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(String.valueOf(response.getBody().get("success"))).isEqualTo("false");
    }

    @Test
    void concurrentEightRequestsAllowExactlyOneConfirmed() throws Exception {
        LocalDate date = LocalDate.now(SEOUL).plusDays(1);
        int hour = 13;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    ResponseEntity<Map> res = restTemplate.postForEntity(
                            url("/api/reservations"),
                            jsonBody("""
                                    {"name":"%s","phone":"0102222%04d","date":"%s","hour":%d}
                                    """.formatted(NAMES[idx], idx, date, hour)),
                            Map.class);
                    if (res.getStatusCode().is2xxSuccessful()) {
                        success.incrementAndGet();
                    } else if (res.getStatusCode() == HttpStatus.CONFLICT) {
                        conflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                }
                return null;
            }));
        }

        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(7);
        assertThat(other.get()).isZero();

        Integer confirmed = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM reservations
                WHERE reservation_date = ? AND reservation_hour = ? AND status = 'CONFIRMED'
                """,
                Integer.class,
                java.sql.Date.valueOf(date),
                hour);
        assertThat(confirmed).isEqualTo(1);
    }

    @Test
    void cancelKeepsHistoryAndAllowsRebook() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(2);
        Reservation first = reservationService.create(request("최지우", "01033334441", date, 14));
        Reservation cancelled = reservationService.cancel(first.getId(), "01033334441");

        assertThat(cancelled.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        Integer cancelledRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reservations WHERE id = ? AND status = 'CANCELLED'",
                Integer.class,
                first.getId());
        assertThat(cancelledRows).isEqualTo(1);

        Reservation second = reservationService.create(request("강하늘", "01033334442", date, 14));
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void searchByPhoneReturnsReservations() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(2);
        reservationService.create(request("윤서연", "01044445555", date, 15));
        List<Reservation> found = reservationService.searchByPhone("010-4444-5555");
        assertThat(found).anyMatch(r -> "01044445555".equals(r.getPhone()) && r.getReservationHour() == 15);
    }

    @Test
    void cancelWithDifferentPhoneIsBlocked() {
        LocalDate date = LocalDate.now(SEOUL).plusDays(2);
        Reservation created = reservationService.create(request("한지민", "01055556661", date, 16));
        assertThatThrownBy(() -> reservationService.cancel(created.getId(), "01055556662"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsInvalidDateHourNameAndPhone() {
        LocalDate today = LocalDate.now(SEOUL);

        assertThatThrownBy(() -> reservationService.create(
                request("홍길동", "01066667777", today.minusDays(1), 10)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> reservationService.create(
                request("홍길동", "01066667777", today.plusDays(10), 10)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> reservationService.create(
                request("홍길동", "01066667777", today.plusDays(1), 12)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> reservationService.create(
                request("A", "01066667777", today.plusDays(1), 17)))
                .isInstanceOf(ApiException.class);

        assertThatThrownBy(() -> reservationService.create(
                request("홍길동", "123", today.plusDays(1), 17)))
                .isInstanceOf(ApiException.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static HttpEntity<String> jsonBody(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private static CreateReservationRequest request(String name, String phone, LocalDate date, int hour) {
        CreateReservationRequest req = new CreateReservationRequest();
        req.setName(name);
        req.setPhone(phone);
        req.setDate(date);
        req.setHour(hour);
        return req;
    }
}
