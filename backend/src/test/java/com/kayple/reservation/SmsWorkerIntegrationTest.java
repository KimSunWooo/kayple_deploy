package com.kayple.reservation;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.SmsStatus;
import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.repository.SmsOutboxRepository;
import com.kayple.reservation.service.ReservationService;
import com.kayple.reservation.sms.SmsWorker;
import com.kayple.reservation.support.MySQLTestcontainer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SmsWorkerIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        Assumptions.assumeTrue(MySQLTestcontainer.isAvailable(), "Docker unavailable");
        if (wireMock == null) {
            wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            wireMock.start();
        }
        var mysql = MySQLTestcontainer.mysql();
        registry.add("DB_URL", mysql::getJdbcUrl);
        registry.add("DB_USERNAME", mysql::getUsername);
        registry.add("DB_PASSWORD", mysql::getPassword);
        registry.add("ADMIN_USERNAME", () -> "admin");
        registry.add("ADMIN_PASSWORD", () -> "adminpass");
        registry.add("SMS_API_KEY", () -> "test-sms-key");
        registry.add("SESSION_COOKIE_SECURE", () -> "false");
        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("app.sms.api-url", () -> "http://127.0.0.1:" + wireMock.port() + "/v1/messages");
        registry.add("app.sms.worker.enabled", () -> "true");
        // Keep scheduler idle; tests invoke SmsWorker.process() explicitly for determinism
        registry.add("app.sms.worker.fixed-delay-ms", () -> "3600000");
        registry.add("app.sms.worker.processing-timeout-seconds", () -> "2");
        registry.add("app.sms.connect-timeout-ms", () -> "1000");
        registry.add("app.sms.read-timeout-ms", () -> "12000");
    }

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(MySQLTestcontainer.isAvailable(), "Docker unavailable");
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
        WireMock.configureFor("127.0.0.1", wireMock.port());
    }

    @LocalServerPort
    int port;
    @Autowired
    ReservationService reservationService;
    @Autowired
    SmsOutboxRepository smsOutboxRepository;
    @Autowired
    SmsWorker smsWorker;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void http200MarksSentWithProviderMessageId() {
        stubJson(200, "{\"messageId\":\"msg_ok_1\",\"status\":\"SENT\"}");
        Reservation created = book("워커성공", "01080001111", 10);
        awaitStatus(created.getId(), SmsStatus.SENT);
        String providerId = jdbcTemplate.queryForObject(
                "SELECT provider_message_id FROM sms_outbox WHERE reservation_id = ? AND event_type = 'BOOKED'",
                String.class,
                created.getId());
        assertThat(providerId).isEqualTo("msg_ok_1");
    }

    @Test
    void http503Then200RetriesUntilSent() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("503then200")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"TEMPORARILY_UNAVAILABLE\",\"message\":\"down\"}"))
                .willSetStateTo("ok"));
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("503then200")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"messageId\":\"msg_retry_ok\",\"status\":\"SENT\"}")));

        Reservation created = book("재시도오삼", "01080002222", 11);
        forceRetryReady(created.getId());
        awaitStatus(created.getId(), SmsStatus.SENT);
    }

    @Test
    void http504KeepsSameIdempotencyKeyAndSingleLogicalSend() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("504then200")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse()
                        .withStatus(504)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"GATEWAY_TIMEOUT\",\"message\":\"timeout\"}"))
                .willSetStateTo("delivered"));
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("504then200")
                .whenScenarioStateIs("delivered")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"messageId\":\"msg_504_once\",\"status\":\"SENT\"}")));

        Reservation created = book("관문타임", "01080003333", 13);
        String key = idempotencyKey(created.getId());
        forceRetryReady(created.getId());
        awaitStatus(created.getId(), SmsStatus.SENT);
        assertThat(idempotencyKey(created.getId())).isEqualTo(key);

        wireMock.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("Idempotency-Key", equalTo(key)));
        // first 504 + second 200, same key — logical send once from provider perspective in our mock
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("Idempotency-Key", equalTo(key))).size()).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_message_id FROM sms_outbox WHERE reservation_id = ?",
                String.class,
                created.getId())).isEqualTo("msg_504_once");
    }

    @Test
    void http409InProgressIsRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("inprogress")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"IN_PROGRESS\",\"message\":\"busy\"}"))
                .willSetStateTo("done"));
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("inprogress")
                .whenScenarioStateIs("done")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"messageId\":\"msg_ip\",\"status\":\"SENT\"}")));

        Reservation created = book("진행중키", "01080004444", 14);
        forceRetryReady(created.getId());
        awaitStatus(created.getId(), SmsStatus.SENT);
    }

    @Test
    void http409IdempotencyKeyReusedFailsPermanently() {
        stubJson(409, "{\"error\":\"IDEMPOTENCY_KEY_REUSED\",\"message\":\"reuse\"}");
        Reservation created = book("키재사용", "01080005555", 15);
        awaitStatus(created.getId(), SmsStatus.FAILED);
        assertThat(attemptCount(created.getId())).isEqualTo(1);
    }

    @Test
    void http429RespectsRetryAfter() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429)
                        .withHeader("Retry-After", "1")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"RATE_LIMITED\",\"message\":\"slow\"}"))
                .willSetStateTo("ok"));
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .inScenario("429")
                .whenScenarioStateIs("ok")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"messageId\":\"msg_429\",\"status\":\"SENT\"}")));

        Reservation created = book("레이트리밋", "01080006666", 16);
        forceRetryReady(created.getId());
        awaitStatus(created.getId(), SmsStatus.SENT);
    }

    @Test
    void http400RecipientBlockedFailsWithoutFurtherRetry() {
        stubJson(400, "{\"error\":\"RECIPIENT_BLOCKED\",\"message\":\"blocked\"}");
        Reservation created = book("수신거부", "01080007777", 17);
        awaitStatus(created.getId(), SmsStatus.FAILED);
        int attempts = attemptCount(created.getId());
        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(attemptCount(created.getId())).isEqualTo(attempts));
        assertThat(status(created.getId())).isEqualTo("FAILED");
    }

    @Test
    void http401FailsPermanently() {
        stubJson(401, "{\"error\":\"UNAUTHORIZED\",\"message\":\"bad key\"}");
        // hour 10 on +4 days to avoid collision with earlier tests' slots on +3/+1
        LocalDate date = LocalDate.now(SEOUL).plusDays(4);
        Reservation created = reservationService.create(req("인증실패", "01080008888", date, 10));
        awaitStatus(created.getId(), SmsStatus.FAILED);
    }

    @Test
    void slowSmsDoesNotBlockReservationApiUnderOneSecond() {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(8000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"messageId\":\"msg_slow\",\"status\":\"SENT\"}")));

        LocalDate date = LocalDate.now(SEOUL).plusDays(4);
        long start = System.nanoTime();
        ResponseEntity<Map> response = restTemplate.postForEntity(
                "http://127.0.0.1:" + port + "/api/reservations",
                json("""
                        {"name":"지연예약","phone":"01080009999","date":"%s","hour":11}
                        """.formatted(date)),
                Map.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(elapsedMs).isLessThan(1000L);
    }

    @Test
    void staleProcessingIsRecovered() {
        stubJson(200, "{\"messageId\":\"msg_recovered\",\"status\":\"SENT\"}");
        // +6 day / hour 11: avoid uk_active_slot collision with book() slots on +5
        LocalDate date = LocalDate.now(SEOUL).plusDays(6);
        Reservation created = reservationService.create(req("복구대상", "01080010001", date, 11));
        Long outboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM sms_outbox WHERE reservation_id = ? AND event_type = 'BOOKED'",
                Long.class,
                created.getId());
        jdbcTemplate.update(
                """
                UPDATE sms_outbox
                SET status = 'PROCESSING',
                    processing_started_at = ?,
                    next_retry_at = ?
                WHERE id = ?
                """,
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(10)),
                java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)),
                outboxId);
        awaitStatus(created.getId(), SmsStatus.SENT);
    }

    @Test
    void concurrentWorkersDoNotDoubleSend() throws Exception {
        stubJson(200, "{\"messageId\":\"msg_once\",\"status\":\"SENT\"}");
        // +6 day / hour 13: avoid uk_active_slot collision with book() slots on +5
        LocalDate date = LocalDate.now(SEOUL).plusDays(6);
        Reservation created = reservationService.create(req("동시워커", "01080010002", date, 13));
        Long outboxId = jdbcTemplate.queryForObject(
                "SELECT id FROM sms_outbox WHERE reservation_id = ?",
                Long.class,
                created.getId());
        jdbcTemplate.update(
                "UPDATE sms_outbox SET status='PENDING', next_retry_at=CURRENT_TIMESTAMP WHERE id=?",
                outboxId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> smsWorker.process());
        pool.submit(() -> smsWorker.process());
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        awaitStatus(created.getId(), SmsStatus.SENT);
        wireMock.verify(exactly(1), postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(containing("01080010002")));
    }

    private void stubJson(int status, String body) {
        wireMock.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private Reservation book(String name, String phone, int hour) {
        LocalDate date = LocalDate.now(SEOUL).plusDays(5);
        return reservationService.create(req(name, phone, date, hour));
    }

    private void forceRetryReady(Long reservationId) {
        jdbcTemplate.update(
                """
                UPDATE sms_outbox
                SET status = 'PENDING', next_retry_at = CURRENT_TIMESTAMP
                WHERE reservation_id = ?
                """,
                reservationId);
    }

    private void awaitStatus(Long reservationId, SmsStatus expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    String current = status(reservationId);
                    if ("PENDING".equals(current) || "PROCESSING".equals(current)) {
                        forceRetryReady(reservationId);
                        smsWorker.process();
                    }
                    assertThat(status(reservationId)).isEqualTo(expected.name());
                });
    }

    private String status(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM sms_outbox WHERE reservation_id = ? AND event_type = 'BOOKED'",
                String.class,
                reservationId);
    }

    private int attemptCount(Long reservationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM sms_outbox WHERE reservation_id = ? AND event_type = 'BOOKED'",
                Integer.class,
                reservationId);
        return count == null ? 0 : count;
    }

    private String idempotencyKey(Long reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM sms_outbox WHERE reservation_id = ? AND event_type = 'BOOKED'",
                String.class,
                reservationId);
    }

    private static CreateReservationRequest req(String name, String phone, LocalDate date, int hour) {
        CreateReservationRequest r = new CreateReservationRequest();
        r.setName(name);
        r.setPhone(phone);
        r.setDate(date);
        r.setHour(hour);
        return r;
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
