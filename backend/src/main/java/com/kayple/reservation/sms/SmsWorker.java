package com.kayple.reservation.sms;

import com.kayple.reservation.domain.SmsOutbox;
import com.kayple.reservation.repository.SmsOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.sms.worker.enabled", havingValue = "true", matchIfMissing = true)
public class SmsWorker {

    private static final Logger log = LoggerFactory.getLogger(SmsWorker.class);
    private static final long[] BACKOFF_SECONDS = {2, 5, 10, 30, 60, 120, 300};

    private final SmsOutboxRepository smsOutboxRepository;
    private final SmsApiClient smsApiClient;
    private final SmsRateLimiter rateLimiter;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int processingTimeoutSeconds;

    public SmsWorker(
            SmsOutboxRepository smsOutboxRepository,
            SmsApiClient smsApiClient,
            SmsRateLimiter rateLimiter,
            PlatformTransactionManager transactionManager,
            @Value("${app.sms.worker.batch-size:5}") int batchSize,
            @Value("${app.sms.worker.processing-timeout-seconds:90}") int processingTimeoutSeconds) {
        this.smsOutboxRepository = smsOutboxRepository;
        this.smsApiClient = smsApiClient;
        this.rateLimiter = rateLimiter;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.batchSize = batchSize;
        this.processingTimeoutSeconds = processingTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${app.sms.worker.fixed-delay-ms:1000}")
    public void process() {
        List<Long> claimedIds = transactionTemplate.execute(status ->
                smsOutboxRepository.claimPending(batchSize, processingTimeoutSeconds));
        if (claimedIds == null || claimedIds.isEmpty()) {
            return;
        }

        for (Long id : claimedIds) {
            processOne(id);
        }
    }

    void processOne(Long id) {
        SmsOutbox outbox = smsOutboxRepository.findById(id).orElse(null);
        if (outbox == null) {
            return;
        }

        if (outbox.getFirstAttemptAt() != null) {
            long hours = ChronoUnit.HOURS.between(outbox.getFirstAttemptAt(), LocalDateTime.now());
            if (hours >= 24) {
                smsOutboxRepository.markFailed(id,
                        "MANUAL_CHECK_REQUIRED: first attempt exceeded 24h idempotency window");
                log.warn("SMS outbox {} exceeded 24h window, marked FAILED for manual check", id);
                return;
            }
        }

        rateLimiter.acquire();
        SmsApiClient.SmsSendResult result = smsApiClient.send(
                outbox.getPhone(),
                outbox.getMessage(),
                outbox.getIdempotencyKey());

        handleResult(id, outbox, result);
    }

    void handleResult(Long id, SmsOutbox outbox, SmsApiClient.SmsSendResult result) {
        int status = result.statusCode();
        String code = result.errorCode();

        if (status == 200) {
            smsOutboxRepository.markSent(id, result.messageId());
            return;
        }

        if (status == 400 && ("INVALID_REQUEST".equals(code) || "RECIPIENT_BLOCKED".equals(code))) {
            smsOutboxRepository.markFailed(id, code + ": " + result.errorMessage());
            return;
        }

        if (status == 401 && "UNAUTHORIZED".equals(code)) {
            smsOutboxRepository.markFailed(id, "UNAUTHORIZED: check SMS_API_KEY configuration");
            log.error("SMS API unauthorized. Check SMS_API_KEY configuration.");
            return;
        }

        if (status == 409 && "IDEMPOTENCY_KEY_REUSED".equals(code)) {
            smsOutboxRepository.markFailed(id, "IDEMPOTENCY_KEY_REUSED: " + result.errorMessage());
            return;
        }

        if (status == 409 && "IN_PROGRESS".equals(code)) {
            scheduleRetry(id, outbox.getAttemptCount(), "IN_PROGRESS");
            return;
        }

        if (status == 429) {
            int retryAfter = result.retryAfterSeconds() != null ? result.retryAfterSeconds() : 2;
            LocalDateTime next = LocalDateTime.now().plusSeconds(Math.max(1, retryAfter));
            smsOutboxRepository.scheduleRetry(id, next, "RATE_LIMITED Retry-After=" + retryAfter);
            return;
        }

        if (status == 503 || status == 504 || result.networkError() || status == -1) {
            String reason = result.networkError()
                    ? "NETWORK: " + result.errorMessage()
                    : status + " " + code;
            scheduleRetry(id, outbox.getAttemptCount(), reason);
            return;
        }

        scheduleRetry(id, outbox.getAttemptCount(), status + " " + code + " " + result.errorMessage());
    }

    private void scheduleRetry(Long id, int attemptCount, String error) {
        long seconds = backoffSeconds(attemptCount);
        LocalDateTime next = LocalDateTime.now().plusSeconds(seconds);
        smsOutboxRepository.scheduleRetry(id, next, error);
    }

    public static long backoffSeconds(int attemptCount) {
        int index = Math.min(Math.max(attemptCount, 0), BACKOFF_SECONDS.length - 1);
        return BACKOFF_SECONDS[index];
    }
}
