package dev.riri.notification;

import jakarta.validation.Valid;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api")
public class NotificationController {
    private final NotificationStore store;
    private final DemoSender sender;

    public NotificationController(NotificationStore store, DemoSender sender) {
        this.store = store;
        this.sender = sender;
    }

    @PostMapping("/notifications")
    public ResponseEntity<AcceptedNotification> send(@Valid @RequestBody SendRequest request) {
        String id = store.enqueue(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/notifications/" + id))
                .body(new AcceptedNotification(id, "QUEUED"));
    }

    @GetMapping("/notifications")
    public List<Notification> recent() { return store.recent(); }

    @GetMapping("/notifications/{id}")
    public Notification get(@PathVariable UUID id) {
        return store.find(id.toString()).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "알림을 찾을 수 없습니다."));
    }

    @GetMapping("/queue")
    public Map<String, Long> queue() {
        return Map.of("pending", store.pendingCount(), "deadLetters", store.deadLetterCount());
    }

    @PostMapping("/demo/send-sync")
    public Map<String, Object> sendSync(@Valid @RequestBody SendRequest request) throws InterruptedException {
        var now = Instant.now();
        var notification = new Notification("sync", request.recipient(), request.message(),
                NotificationStatus.PROCESSING, 1, request.demoFailures(), request.demoDelayMs(), now, now, now, "");
        long start = System.nanoTime();
        sender.send(notification, 1);
        return Map.of("status", "SENT", "elapsedMs", (System.nanoTime() - start) / 1_000_000);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요.");
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
        return problem;
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail redisUnavailable(DataAccessException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    @ExceptionHandler(DemoSender.SendFailedException.class)
    public ProblemDetail sendFailed(DemoSender.SendFailedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    public record AcceptedNotification(String id, String status) {}
}
