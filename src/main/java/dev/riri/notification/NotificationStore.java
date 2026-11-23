package dev.riri.notification;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationStore {
    static final String GROUP = "senders";
    // 첫 버전은 워커 하나를 사용한다. 재시작해도 같은 소비자의 pending을 읽는다.
    static final String CONSUMER = "worker-1";

    private final StringRedisTemplate redis;
    private final String prefix;
    private final DefaultRedisScript<String> enqueueScript;
    private final DefaultRedisScript<Long> finishScript;

    public NotificationStore(StringRedisTemplate redis, NotificationSettings settings) {
        this.redis = redis;
        this.prefix = settings.keyPrefix();
        this.enqueueScript = new DefaultRedisScript<>();
        enqueueScript.setLocation(new ClassPathResource("redis/enqueue.lua"));
        enqueueScript.setResultType(String.class);
        this.finishScript = new DefaultRedisScript<>();
        finishScript.setLocation(new ClassPathResource("redis/finish.lua"));
        finishScript.setResultType(Long.class);
    }

    public String enqueue(SendRequest request) {
        String id = UUID.randomUUID().toString();
        String now = Long.toString(System.currentTimeMillis());
        redis.execute(enqueueScript, List.of(streamKey(), itemKey(id), prefix + ":recent"),
                id, request.recipient(), request.message(),
                Integer.toString(request.demoFailures()), Integer.toString(request.demoDelayMs()), now);
        return id;
    }

    public Optional<Notification> find(String id) {
        Map<Object, Object> values = redis.opsForHash().entries(itemKey(id));
        return values.isEmpty() ? Optional.empty() : Optional.of(Notification.from(values));
    }

    public List<Notification> recent() {
        var ids = redis.opsForZSet().reverseRange(prefix + ":recent", 0, 49);
        if (ids == null) return List.of();
        return ids.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    public void createGroup() {
        // MKSTREAM으로 비어 있는 큐도 함께 만든다.
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                connection.streamCommands().xGroupCreate(
                        streamKey().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        GROUP, ReadOffset.from("0-0"), true));
    }

    public List<MapRecord<String, Object, Object>> read(String offset) {
        return redis.opsForStream().read(Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(streamKey(), ReadOffset.from(offset)));
    }

    public int beginAttempt(String id) {
        Long attempts = redis.opsForHash().increment(itemKey(id), "attempts", 1);
        redis.opsForHash().putAll(itemKey(id), Map.of(
                "status", NotificationStatus.PROCESSING.name(),
                "updatedAt", Long.toString(System.currentTimeMillis())));
        return attempts.intValue();
    }

    public void waitForRetry(String id, String error, Instant nextRetryAt) {
        redis.opsForHash().putAll(itemKey(id), Map.of(
                "status", NotificationStatus.RETRY_WAIT.name(),
                "lastError", error,
                "nextRetryAt", Long.toString(nextRetryAt.toEpochMilli()),
                "updatedAt", Long.toString(System.currentTimeMillis())));
    }

    public void finish(String id, String recordId, NotificationStatus status, String error) {
        redis.execute(finishScript, List.of(itemKey(id), streamKey(), deadLetterKey()),
                status.name(), Long.toString(System.currentTimeMillis()), error, GROUP, recordId, id);
    }

    public void acknowledge(String recordId) {
        redis.opsForStream().acknowledge(streamKey(), GROUP, recordId);
    }

    public long pendingCount() {
        var pending = redis.opsForStream().pending(streamKey(), GROUP);
        return pending == null ? 0 : pending.getTotalPendingMessages();
    }

    public long deadLetterCount() {
        Long count = redis.opsForStream().size(deadLetterKey());
        return count == null ? 0 : count;
    }

    public String streamKey() { return prefix + ":stream"; }
    public String deadLetterKey() { return prefix + ":dead"; }
    private String itemKey(String id) { return prefix + ":item:" + id; }
}
