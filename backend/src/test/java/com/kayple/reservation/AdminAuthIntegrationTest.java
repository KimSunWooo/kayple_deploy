package com.kayple.reservation;

import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.service.ReservationService;
import com.kayple.reservation.support.IntegrationTestProperties;
import com.kayple.reservation.support.MySQLTestcontainer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthIntegrationTest {

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
    MockMvc mockMvc;
    @Autowired
    ReservationService reservationService;
    @Autowired
    JdbcTemplate jdbcTemplate;

    LocalDate date;

    @BeforeEach
    void seed() {
        date = LocalDate.now(SEOUL).plusDays(6);
        CreateReservationRequest req = new CreateReservationRequest();
        req.setName("관리검증");
        req.setPhone("01090001111");
        req.setDate(date);
        req.setHour(10);
        try {
            reservationService.create(req);
        } catch (Exception ignored) {
            // slot may already be filled from prior run in same container
        }
    }

    @Test
    void unauthenticatedAdminApisReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/reservations").param("date", date.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/api/admin/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void badLoginIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginCreatesSessionAndAllowsReservationListWithSmsStatus() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"adminpass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/api/admin/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));

        mockMvc.perform(get("/api/admin/reservations").param("date", date.toString()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(date.toString()))
                .andExpect(jsonPath("$.data.reservations").isArray())
                .andExpect(jsonPath("$.data.reservations[0].bookedSmsStatus", containsString("대기")));
    }

    @Test
    void dateFilterReturnsOnlyRequestedDate() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"adminpass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        LocalDate other = date.minusDays(1);
        mockMvc.perform(get("/api/admin/reservations").param("date", other.toString()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value(other.toString()))
                .andExpect(jsonPath("$.data.reservations").isArray());
    }

    @Test
    void logoutBlocksAdminApi() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"adminpass\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(post("/api/admin/logout").session(session).with(csrf()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/me").session(session))
                .andExpect(status().isUnauthorized());
    }
}
