package com.kayple.reservation;

import com.kayple.reservation.domain.SmsEventType;
import com.kayple.reservation.domain.SmsOutbox;
import com.kayple.reservation.domain.SmsStatus;
import com.kayple.reservation.repository.SmsOutboxRepository;
import com.kayple.reservation.sms.SmsApiClient;
import com.kayple.reservation.sms.SmsRateLimiter;
import com.kayple.reservation.sms.SmsWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsWorkerTest {

    @Mock
    SmsOutboxRepository smsOutboxRepository;
    @Mock
    SmsApiClient smsApiClient;
    @Mock
    PlatformTransactionManager transactionManager;

    SmsWorker worker;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        worker = new SmsWorker(
                smsOutboxRepository,
                smsApiClient,
                new SmsRateLimiter(5),
                transactionManager,
                5,
                90);
    }

    @Test
    void retriesAfter503WithBackoff() {
        SmsOutbox outbox = sample(0);
        when(smsOutboxRepository.claimPending(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(smsOutboxRepository.findById(1L)).thenReturn(Optional.of(outbox));
        when(smsApiClient.send(anyString(), anyString(), anyString()))
                .thenReturn(new SmsApiClient.SmsSendResult(503, null, "TEMPORARILY_UNAVAILABLE", "down", null, false));

        worker.process();

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(smsOutboxRepository).scheduleRetry(eq(1L), next.capture(), anyString());
        assertThat(next.getValue()).isAfter(LocalDateTime.now().minusSeconds(1));
        verify(smsOutboxRepository, never()).markFailed(anyLong(), anyString());
        verify(smsOutboxRepository, never()).markSent(anyLong(), anyString());
    }

    @Test
    void keepsSameIdempotencyKeyOn504() {
        SmsOutbox outbox = sample(1);
        outbox.setPhone("01000000504");
        String key = outbox.getIdempotencyKey();
        when(smsOutboxRepository.claimPending(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(smsOutboxRepository.findById(1L)).thenReturn(Optional.of(outbox));
        when(smsApiClient.send(eq("01000000504"), anyString(), eq(key)))
                .thenReturn(new SmsApiClient.SmsSendResult(504, null, "GATEWAY_TIMEOUT", "timeout", null, false));

        worker.process();

        verify(smsApiClient).send(eq("01000000504"), anyString(), eq(key));
        verify(smsOutboxRepository).scheduleRetry(eq(1L), any(), anyString());
    }

    @Test
    void marksRecipientBlockedAsFailed() {
        SmsOutbox outbox = sample(0);
        outbox.setPhone("01000000400");
        when(smsOutboxRepository.claimPending(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(smsOutboxRepository.findById(1L)).thenReturn(Optional.of(outbox));
        when(smsApiClient.send(anyString(), anyString(), anyString()))
                .thenReturn(new SmsApiClient.SmsSendResult(400, null, "RECIPIENT_BLOCKED", "blocked", null, false));

        worker.process();

        verify(smsOutboxRepository).markFailed(eq(1L), anyString());
        verify(smsOutboxRepository, never()).scheduleRetry(anyLong(), any(), anyString());
    }

    @Test
    void respectsRetryAfterOn429() {
        SmsOutbox outbox = sample(0);
        when(smsOutboxRepository.claimPending(anyInt(), anyInt())).thenReturn(List.of(1L));
        when(smsOutboxRepository.findById(1L)).thenReturn(Optional.of(outbox));
        when(smsApiClient.send(anyString(), anyString(), anyString()))
                .thenReturn(new SmsApiClient.SmsSendResult(429, null, "RATE_LIMITED", "slow down", 7, false));

        worker.process();

        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(smsOutboxRepository).scheduleRetry(eq(1L), next.capture(), anyString());
        assertThat(next.getValue()).isAfter(LocalDateTime.now().plusSeconds(5));
    }

    @Test
    void backoffScheduleIncreases() {
        assertThat(SmsWorker.backoffSeconds(0)).isEqualTo(2);
        assertThat(SmsWorker.backoffSeconds(1)).isEqualTo(5);
        assertThat(SmsWorker.backoffSeconds(2)).isEqualTo(10);
        assertThat(SmsWorker.backoffSeconds(3)).isEqualTo(30);
        assertThat(SmsWorker.backoffSeconds(10)).isEqualTo(300);
    }

    private SmsOutbox sample(int attempts) {
        SmsOutbox o = new SmsOutbox();
        o.setId(1L);
        o.setReservationId(10L);
        o.setEventType(SmsEventType.BOOKED);
        o.setPhone("01012345678");
        o.setMessage("[케이플 상담] 11월 28일(금) 14:00 상담이 예약되었습니다.");
        o.setIdempotencyKey("booked-10-fixed-key");
        o.setStatus(SmsStatus.PROCESSING);
        o.setAttemptCount(attempts);
        return o;
    }
}
