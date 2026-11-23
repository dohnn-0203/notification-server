package dev.riri.notification;

import org.springframework.stereotype.Component;

@Component
public class DemoSender {
    public void send(Notification notification, int attempt) throws InterruptedException {
        // 외부 발송 API의 지연과 실패를 흉내 낸다.
        Thread.sleep(notification.demoDelayMs());
        if (attempt <= notification.demoFailures()) {
            throw new SendFailedException("테스트 발송 실패 (시도 " + attempt + ")");
        }
    }

    public static class SendFailedException extends RuntimeException {
        public SendFailedException(String message) { super(message); }
    }
}
