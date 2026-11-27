package com.kayple.reservation;

import com.kayple.reservation.domain.Reservation;
import com.kayple.reservation.domain.ReservationStatus;
import com.kayple.reservation.domain.SmsEventType;
import com.kayple.reservation.domain.SmsOutbox;
import com.kayple.reservation.dto.CreateReservationRequest;
import com.kayple.reservation.exception.ApiException;
import com.kayple.reservation.repository.ReservationRepository;
import com.kayple.reservation.repository.SmsOutboxRepository;
import com.kayple.reservation.service.NameValidator;
import com.kayple.reservation.service.PhoneValidator;
import com.kayple.reservation.service.ReservationService;
import com.kayple.reservation.service.SmsMessageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    ReservationRepository reservationRepository;
    @Mock
    SmsOutboxRepository smsOutboxRepository;

    ReservationService service;
    Clock clock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-27T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new ReservationService(
                reservationRepository,
                smsOutboxRepository,
                new PhoneValidator(),
                new NameValidator(),
                new SmsMessageFactory(),
                clock);
    }

    @Test
    void createInsertsReservationAndOutboxTogether() {
        when(reservationRepository.insert(any())).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(100L);
            return r;
        });
        when(smsOutboxRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateReservationRequest req = request("홍길동", "010-1234-5678", LocalDate.of(2026, 11, 28), 14);
        Reservation created = service.create(req);

        assertThat(created.getId()).isEqualTo(100L);
        assertThat(created.getPhone()).isEqualTo("01012345678");

        ArgumentCaptor<SmsOutbox> outboxCaptor = ArgumentCaptor.forClass(SmsOutbox.class);
        InOrder order = inOrder(reservationRepository, smsOutboxRepository);
        order.verify(reservationRepository).insert(any());
        order.verify(smsOutboxRepository).insert(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo(SmsEventType.BOOKED);
        assertThat(outboxCaptor.getValue().getMessage()).contains("11월 28일").contains("14:00");
        assertThat(outboxCaptor.getValue().getIdempotencyKey()).startsWith("booked-100-");
    }

    @Test
    void duplicateKeyPropagatesFor409() {
        when(reservationRepository.insert(any())).thenThrow(new DuplicateKeyException("dup"));
        assertThatThrownBy(() -> service.create(
                request("홍길동", "01012345678", LocalDate.of(2026, 11, 28), 14)))
                .isInstanceOf(DuplicateKeyException.class);
        verify(smsOutboxRepository, never()).insert(any());
    }

    @Test
    void cancelCreatesSingleOutboxAndRejectsDuplicateCancel() {
        Reservation confirmed = new Reservation();
        confirmed.setId(5L);
        confirmed.setPhone("01012345678");
        confirmed.setCustomerName("홍길동");
        confirmed.setReservationDate(LocalDate.of(2026, 11, 28));
        confirmed.setReservationHour(14);
        confirmed.setStatus(ReservationStatus.CONFIRMED);

        Reservation cancelled = new Reservation();
        cancelled.setId(5L);
        cancelled.setPhone("01012345678");
        cancelled.setCustomerName("홍길동");
        cancelled.setReservationDate(LocalDate.of(2026, 11, 28));
        cancelled.setReservationHour(14);
        cancelled.setStatus(ReservationStatus.CANCELLED);

        when(reservationRepository.findById(5L))
                .thenReturn(Optional.of(confirmed))
                .thenReturn(Optional.of(cancelled))
                .thenReturn(Optional.of(cancelled));
        when(reservationRepository.cancelIfConfirmed(5L, "01012345678"))
                .thenReturn(1)
                .thenReturn(0);
        when(smsOutboxRepository.insert(any())).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(5L, "010-1234-5678");
        verify(smsOutboxRepository, times(1)).insert(any());

        assertThatThrownBy(() -> service.cancel(5L, "01012345678"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 취소");
        verify(smsOutboxRepository, times(1)).insert(any());
    }

    @Test
    void createDoesNotCallExternalSms() {
        AtomicBoolean smsCalled = new AtomicBoolean(false);
        when(reservationRepository.insert(any())).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        when(smsOutboxRepository.insert(any())).thenAnswer(inv -> {
            // outbox only — no HTTP
            assertThat(smsCalled.get()).isFalse();
            return inv.getArgument(0);
        });

        long start = System.nanoTime();
        service.create(request("홍길동", "01012345678", LocalDate.of(2026, 11, 28), 14));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(1000L);
        assertThat(smsCalled.get()).isFalse();
    }

    private CreateReservationRequest request(String name, String phone, LocalDate date, int hour) {
        CreateReservationRequest req = new CreateReservationRequest();
        req.setName(name);
        req.setPhone(phone);
        req.setDate(date);
        req.setHour(hour);
        return req;
    }
}
