package com.kayple.reservation.controller;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.dto.ApiResponse;
import com.kayple.reservation.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AuthenticationManager authenticationManager;
    private final ReservationService reservationService;
    private final String adminUsername;

    public AdminController(
            AuthenticationManager authenticationManager,
            ReservationService reservationService,
            @Value("${app.admin.username}") String adminUsername) {
        this.authenticationManager = authenticationManager;
        this.reservationService = reservationService;
        this.adminUsername = adminUsername;
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = body.getOrDefault("username", "");
        String password = body.getOrDefault("password", "");

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        HttpSession session = request.getSession(true);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

        return ApiResponse.ok(Map.of("username", authentication.getName()));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, String>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.ok(Map.of("status", "logged_out"));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return ApiResponse.ok(Map.of("username", authentication.getName()));
    }

    @GetMapping("/reservations")
    public ApiResponse<Map<String, Object>> reservations(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<Reservation> list = reservationService.findByDate(date);
        List<Long> ids = list.stream().map(Reservation::getId).toList();
        Map<Long, Map<String, String>> sms = reservationService.smsStatuses(ids);

        List<Map<String, Object>> items = list.stream().map(r -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", r.getId());
            item.put("name", r.getCustomerName());
            item.put("phone", r.getPhone());
            item.put("date", r.getReservationDate().toString());
            item.put("hour", r.getReservationHour());
            item.put("status", r.getStatus().name());
            Map<String, String> statuses = sms.getOrDefault(r.getId(), Map.of());
            item.put("bookedSmsStatus", toDisplay(statuses.get("BOOKED")));
            item.put("cancelledSmsStatus", toDisplay(statuses.get("CANCELLED")));
            return item;
        }).toList();

        return ApiResponse.ok(Map.of(
                "date", date.toString(),
                "reservations", items
        ));
    }

    private String toDisplay(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "SENT" -> "발송 완료";
            case "FAILED" -> "실패";
            case "PENDING", "PROCESSING" -> "대기";
            default -> status;
        };
    }
}
