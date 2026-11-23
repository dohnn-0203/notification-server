package dev.riri.notification;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component
public class QueueHealthIndicator implements HealthIndicator {
    private final RedisConnectionFactory connectionFactory;

    public QueueHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        // Windows 테스트 Redis의 INFO 경로 대신 PING으로 연결 확인
        try (var connection = connectionFactory.getConnection()) {
            return "PONG".equals(connection.ping()) ? Health.up().build() : Health.down().build();
        } catch (Exception e) {
            return Health.down().build();
        }
    }
}
