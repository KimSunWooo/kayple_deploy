package com.kayple.reservation.controller;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.dto.ApiResponse;
import com.kayple.reservation.dto.CancelReservationRequest;
import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.dto.SearchReservationRequest;
import com.kayple.reservation.service.ReservationService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP"));
    }

    @GetMapping("/availability")
    public ApiResponse<Map<String, Object>> availability(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(reservationService.getAvailability(date));
    }

    @PostMapping("/reservations")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateReservationRequest request) {
        Reservation reservation = reservationService.create(request);
        return ApiResponse.ok(toDto(reservation));
    }

    @PostMapping("/reservations/search")
    public ApiResponse<Map<String, Object>> search(@Valid @RequestBody SearchReservationRequest request) {
        List<Reservation> list = reservationService.searchByPhone(request.getPhone());
        return ApiResponse.ok(Map.of(
                "reservations", list.stream().map(this::toDto).toList()
        ));
    }

    @PostMapping("/reservations/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(
            @PathVariable Long id,
            @Valid @RequestBody CancelReservationRequest request) {
        Reservation reservation = reservationService.cancel(id, request.getPhone());
        return ApiResponse.ok(toDto(reservation));
    }

    private Map<String, Object> toDto(Reservation reservation) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", reservation.getId());
        map.put("name", reservation.getCustomerName());
        map.put("phone", reservation.getPhone());
        map.put("date", reservation.getReservationDate().toString());
        map.put("hour", reservation.getReservationHour());
        map.put("status", reservation.getStatus().name());
        map.put("createdAt", reservation.getCreatedAt());
        map.put("cancelledAt", reservation.getCancelledAt());
        return map;
    }
}
