package dev.riri.notification;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import redis.embedded.RedisServer;

import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"notification.worker-enabled=false", "notification.retry-delay-ms=30"})
@AutoConfigureMockMvc
class NotificationIntegrationTest {
    static RedisServer redisServer;
    static int port;
    @Autowired NotificationStore store;
    @Autowired NotificationWorker worker;
    @Autowired NotificationSettings settings;
    @Autowired DemoSender sender;
    @Autowired StringRedisTemplate redis;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        redisServer = RedisServer.newRedisServer().port(port)
                .setting("bind 127.0.0.1").setting("maxmemory 64mb")
                .setting("save \"\"").setting("appendonly no").build();
        redisServer.start();
        registry.add("spring.data.redis.port", () -> port);
        registry.add("notification.key-prefix", () -> "test-notification");
    }

    @AfterAll
    static void stopRedis(@Autowired org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory) throws Exception {
        factory.destroy();
        if (redisServer != null) redisServer.stop();
    }

    @BeforeEach
    void clearTestData() {
        // 테스트 전용 Redis 인스턴스만 초기화
        try (var connection = redis.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        store.createGroup();
        worker = new NotificationWorker(store, sender, settings);
    }

    @Test
    void acceptsBeforeSendingAndProvidesLocation() throws Exception {
        mvc.perform(post("/api/notifications").contentType("application/json")
                        .content("{\"recipient\":\"tester\",\"message\":\"hello\",\"demoDelayMs\":1500}"))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(store.recent()).hasSize(1);
        assertThat(store.recent().get(0).attempts()).isZero();
    }

    @Test
    void successfulMessageIsAcknowledged() {
        String id = store.enqueue(new SendRequest("tester", "hello", 0, 0));
        worker.tick();
        Notification result = store.find(id).orElseThrow();
        assertThat(result.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    void retriesTwiceThenSucceeds() {
        String id = store.enqueue(new SendRequest("tester", "retry", 2, 0));
        worker.tick();
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(NotificationStatus.RETRY_WAIT);
        assertThat(store.pendingCount()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
            worker.tick();
            assertThat(store.find(id).orElseThrow().status()).isEqualTo(NotificationStatus.SENT);
        });
        assertThat(store.find(id).orElseThrow().attempts()).isEqualTo(3);
        assertThat(store.pendingCount()).isZero();
        assertThat(store.deadLetterCount()).isZero();
    }

    @Test
    void exhaustedMessageIsStoredOnceAndAcknowledged() {
        String id = store.enqueue(new SendRequest("tester", "fail", 10, 0));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(25)).untilAsserted(() -> {
            worker.tick();
            assertThat(store.find(id).orElseThrow().status()).isEqualTo(NotificationStatus.FAILED);
        });
        assertThat(store.find(id).orElseThrow().attempts()).isEqualTo(3);
        assertThat(store.pendingCount()).isZero();
        assertThat(store.deadLetterCount()).isEqualTo(1);
        worker.tick();
        assertThat(store.deadLetterCount()).isEqualTo(1);
    }

    @Test
    void newWorkerResumesAnUnacknowledgedMessage() {
        String id = store.enqueue(new SendRequest("tester", "recover", 0, 0));
        store.read(">");
        store.beginAttempt(id);
        assertThat(store.pendingCount()).isEqualTo(1);
        NotificationWorker restarted = new NotificationWorker(store, sender, settings);
        restarted.run(new DefaultApplicationArguments(new String[0]));
        restarted.tick();
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(store.find(id).orElseThrow().attempts()).isEqualTo(2);
        assertThat(store.pendingCount()).isZero();
    }

    @Test
    void waitingRetryDoesNotBlockNewMessages() {
        String waiting = store.enqueue(new SendRequest("tester", "later", 0, 0));
        store.read(">");
        store.waitForRetry(waiting, "wait", Instant.now().plusSeconds(60));
        String fresh = store.enqueue(new SendRequest("tester", "now", 0, 0));
        worker.tick();
        assertThat(store.find(fresh).orElseThrow().status()).isEqualTo(NotificationStatus.SENT);
        assertThat(store.find(waiting).orElseThrow().attempts()).isZero();
    }

    @Test
    void interruptedFinalAttemptIsNotSentAgainAutomatically() {
        String id = store.enqueue(new SendRequest("tester", "unknown result", 0, 0));
        store.read(">");
        for (int attempt = 0; attempt < settings.maxAttempts(); attempt++) store.beginAttempt(id);
        worker.tick();
        assertThat(store.find(id).orElseThrow().status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(store.find(id).orElseThrow().attempts()).isEqualTo(3);
        assertThat(store.find(id).orElseThrow().lastError()).contains("결과 확인 필요");
        assertThat(store.deadLetterCount()).isEqualTo(1);
    }

    @Test
    void invalidRequestsDoNotEnterTheQueue() throws Exception {
        mvc.perform(post("/api/notifications").contentType("application/json")
                        .content("{\"recipient\":\" \",\"message\":\"hello\",\"demoDelayMs\":9999}"))
                .andExpect(status().isBadRequest());
        assertThat(store.recent()).isEmpty();
        mvc.perform(get("/api/notifications/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }
}
