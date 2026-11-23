package dev.riri.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class NotificationWorker implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);
    private final NotificationStore store;
    private final DemoSender sender;
    private final NotificationSettings settings;
    private volatile boolean ready;
    private String pendingCursor = "0-0";

    public NotificationWorker(NotificationStore store, DemoSender sender, NotificationSettings settings) {
        this.store = store;
        this.sender = sender;
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            store.createGroup();
        } catch (RuntimeException e) {
            // 이미 만들어진 그룹만 재사용
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            if (cause.getMessage() == null || !cause.getMessage().contains("BUSYGROUP")) throw e;
        }
        ready = true;
    }

    @Scheduled(fixedDelayString = "${notification.poll-delay-ms:300}")
    public void poll() {
        if (!ready || !settings.workerEnabled()) return;
        try {
            tick();
        } catch (RuntimeException e) {
            // Redis 장애일 때 ACK하지 않고 다음 주기에 다시 확인
            log.warn("워커 처리 중단: {}", e.getMessage());
        }
    }

    public void tick() {
        List<MapRecord<String, Object, Object>> pending = store.read(pendingCursor);
        if (pending == null || pending.isEmpty()) {
            pendingCursor = "0-0";
        } else {
            pendingCursor = pending.get(0).getId().getValue();
            process(pending.get(0));
        }

        // 재시도 대기 중에도 새 요청을 읽는다.
        List<MapRecord<String, Object, Object>> fresh = store.read(">");
        if (fresh != null && !fresh.isEmpty()) process(fresh.get(0));
    }

    private void process(MapRecord<String, Object, Object> record) {
        String id = record.getValue().get("notificationId").toString();
        String recordId = record.getId().getValue();
        Notification notification = store.find(id)
                .orElseThrow(() -> new IllegalStateException("발송 상태가 없습니다: " + id));

        if (notification.status() == NotificationStatus.SENT || notification.status() == NotificationStatus.FAILED) {
            store.acknowledge(recordId);
            return;
        }
        if (notification.nextRetryAt().isAfter(Instant.now())) return;
        if (notification.attempts() >= settings.maxAttempts()) {
            store.finish(id, recordId, NotificationStatus.FAILED, "처리 중단 후 최대 시도 횟수 도달. 발송 결과 확인 필요");
            return;
        }

        int attempt = store.beginAttempt(id);
        log.info("발송 시작 id={} attempt={}", id, attempt);
        try {
            sender.send(notification, attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (DemoSender.SendFailedException e) {
            if (attempt >= settings.maxAttempts()) {
                store.finish(id, recordId, NotificationStatus.FAILED, e.getMessage());
                log.warn("최종 실패 id={} attempt={}", id, attempt);
            } else {
                long delay = settings.retryDelayMs() * (1L << Math.min(attempt - 1, 10));
                store.waitForRetry(id, e.getMessage(), Instant.now().plusMillis(delay));
                log.info("재시도 대기 id={} delayMs={}", id, delay);
            }
            return;
        }

        // 발송 성공 뒤에만 완료 기록
        store.finish(id, recordId, NotificationStatus.SENT, "");
        log.info("발송 완료 id={} attempt={}", id, attempt);
    }
}
