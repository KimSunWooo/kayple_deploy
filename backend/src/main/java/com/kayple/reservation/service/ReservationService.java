package com.kayple.reservation.service;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.ReservationStatus;
import com.kayple.reservation.domain.SmsEventType;
import com.kayple.reservation.domain.SmsOutbox;
import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.exception.ApiException;
import com.kayple.reservation.repository.ReservationRepository;
import com.kayple.reservation.repository.SmsOutboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReservationService {

    public static final List<Integer> AVAILABLE_HOURS = List.of(10, 11, 13, 14, 15, 16, 17);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ReservationRepository reservationRepository;
    private final SmsOutboxRepository smsOutboxRepository;
    private final PhoneValidator phoneValidator;
    private final NameValidator nameValidator;
    private final SmsMessageFactory smsMessageFactory;
    private final Clock clock;

    public ReservationService(
            ReservationRepository reservationRepository,
            SmsOutboxRepository smsOutboxRepository,
            PhoneValidator phoneValidator,
            NameValidator nameValidator,
            SmsMessageFactory smsMessageFactory,
            Clock clock) {
        this.reservationRepository = reservationRepository;
        this.smsOutboxRepository = smsOutboxRepository;
        this.phoneValidator = phoneValidator;
        this.nameValidator = nameValidator;
        this.smsMessageFactory = smsMessageFactory;
        this.clock = clock;
    }

    public Map<String, Object> getAvailability(LocalDate date) {
        validateDateRange(date);
        Set<Integer> booked = new HashSet<>(reservationRepository.findConfirmedHours(date));
        LocalDateTime now = LocalDateTime.now(clock.withZone(SEOUL));
        List<Map<String, Object>> slots = AVAILABLE_HOURS.stream().map(hour -> {
            Map<String, Object> slot = new HashMap<>();
            slot.put("hour", hour);
            boolean past = date.equals(now.toLocalDate()) && hour <= now.getHour();
            boolean closed = booked.contains(hour) || past;
            slot.put("available", !closed);
            slot.put("label", closed ? (booked.contains(hour) ? "마감" : "마감") : String.format("%02d:00", hour));
            slot.put("reason", booked.contains(hour) ? "BOOKED" : (past ? "PAST" : null));
            return slot;
        }).toList();

        Map<String, Object> result = new HashMap<>();
        result.put("date", date.toString());
        result.put("slots", slots);
        return result;
    }

    @Transactional
    public Reservation create(CreateReservationRequest request) {
        String name = nameValidator.normalize(request.getName());
        String phone = phoneValidator.normalize(request.getPhone());
        LocalDate date = request.getDate();
        int hour = request.getHour();

        validateDateRange(date);
        if (!AVAILABLE_HOURS.contains(hour)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_HOUR", "예약할 수 없는 시간입니다.");
        }

        LocalDateTime now = LocalDateTime.now(clock.withZone(SEOUL));
        if (date.equals(now.toLocalDate()) && hour <= now.getHour()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PAST_SLOT", "이미 지난 시간은 예약할 수 없습니다.");
        }

        Reservation reservation = new Reservation();
        reservation.setCustomerName(name);
        reservation.setPhone(phone);
        reservation.setReservationDate(date);
        reservation.setReservationHour(hour);
        reservation.setStatus(ReservationStatus.CONFIRMED);

        try {
            reservationRepository.insert(reservation);
        } catch (DuplicateKeyException e) {
            throw e;
        }

        SmsOutbox outbox = new SmsOutbox();
        outbox.setReservationId(reservation.getId());
        outbox.setEventType(SmsEventType.BOOKED);
        outbox.setPhone(phone);
        outbox.setMessage(smsMessageFactory.create(reservation, SmsEventType.BOOKED));
        outbox.setIdempotencyKey("booked-" + reservation.getId() + "-" + UUID.randomUUID());
        smsOutboxRepository.insert(outbox);

        return reservation;
    }

    public List<Reservation> searchByPhone(String rawPhone) {
        String phone = phoneValidator.normalize(rawPhone);
        return reservationRepository.findByPhone(phone);
    }

    @Transactional
    public Reservation cancel(Long id, String rawPhone) {
        String phone = phoneValidator.normalize(rawPhone);
        Reservation existing = reservationRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "예약을 찾을 수 없습니다."));

        if (!existing.getPhone().equals(phone)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PHONE_MISMATCH", "예약 연락처가 일치하지 않습니다.");
        }

        int updated = reservationRepository.cancelIfConfirmed(id, phone);
        if (updated == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_CANCELLED", "이미 취소된 예약입니다.");
        }

        Reservation cancelled = reservationRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "예약을 찾을 수 없습니다."));

        SmsOutbox outbox = new SmsOutbox();
        outbox.setReservationId(cancelled.getId());
        outbox.setEventType(SmsEventType.CANCELLED);
        outbox.setPhone(phone);
        outbox.setMessage(smsMessageFactory.create(cancelled, SmsEventType.CANCELLED));
        outbox.setIdempotencyKey("cancelled-" + cancelled.getId() + "-" + UUID.randomUUID());
        smsOutboxRepository.insert(outbox);

        return cancelled;
    }

    public List<Reservation> findByDate(LocalDate date) {
        return reservationRepository.findByDate(date);
    }

    public Map<Long, Map<String, String>> smsStatuses(List<Long> ids) {
        return smsOutboxRepository.findStatusesByReservationIds(ids);
    }

    private void validateDateRange(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        if (date.isBefore(today) || date.isAfter(today.plusDays(6))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "오늘부터 7일 이내의 날짜만 선택할 수 있습니다.");
        }
    }
}
