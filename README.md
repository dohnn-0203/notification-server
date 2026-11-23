# notification-server

Spring Boot와 Redis Streams로 만든 알림 발송 서버.

알림 발송이 늦어질 때 API 응답도 같이 느려지는 상황을 확인하고, 접수와 발송 처리를 분리해 봤다. 요청은 Redis에 저장한 뒤 응답하고, 워커가 가져가서 처리한다. 실패한 작업의 재시도와 애플리케이션 재시작 후 복구까지 구현했다.

현재는 지연과 실패 횟수를 설정할 수 있는 `DemoSender`로 동작을 확인한다. 실제 문자나 이메일 발송은 연결하지 않았다.

## 구현한 기능

- 알림 접수 후 `202 Accepted`와 상태 조회 주소 반환
- Redis Streams 소비자 그룹을 이용한 비동기 처리
- 최대 3회 시도, 실패 후 1초·2초 간격으로 재시도
- 최종 실패한 작업을 별도 Stream에 기록
- 애플리케이션 재시작 시 미확인 작업 다시 처리
- 브라우저에서 접수 결과와 발송 상태 확인

## 개발 환경

| 항목 | 버전 |
| --- | --- |
| Java | 17 |
| Spring Boot | 3.5.16 |
| Maven | 3.9.16, Wrapper 포함 |
| Redis | 로컬 테스트 5.0.14.1 / Docker 설정 7.4 |

Windows에서 빌드와 실행을 확인했다. Docker 실행 경로는 아직 검증하지 않았다.

## 실행 방법

Java 17 이상이 필요하다. Maven은 Wrapper로 실행한다.

### Docker 사용

프로젝트 폴더에서 Redis를 먼저 실행한다.

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run
```

<http://127.0.0.1:8080>에 접속하면 테스트 화면이 나온다. Redis는 로컬 16379 포트를 사용한다.
macOS/Linux에서는 `./mvnw spring-boot:run`으로 실행한다.

Redis 데이터는 Docker volume에 저장한다. AOF의 기본 동기화 간격은 1초이므로 장애 시 최근 쓰기가 유실될 수 있다.

### Docker 없이 Windows에서 실행

```powershell
.\scripts\start-local.ps1
```

빌드와 테스트를 마친 뒤 로컬 Redis와 서버를 실행한다. 8080 포트를 사용 중이라면 다음처럼 바꿀 수 있다.

```powershell
.\scripts\start-local.ps1 -Port 8087
```

로그는 `.runtime`에서 확인한다. 종료할 때는 아래 스크립트를 사용한다.

```powershell
.\scripts\stop-local.ps1
```

이 방식은 `embedded-redis`에 포함된 Windows Redis 5.0.14.1을 사용한다. **Redis를 종료하면 데이터가 사라진다.** 서버만 재시작하는 경우에는 Redis에 남은 작업을 이어서 처리한다.

## 동작 확인

화면에서 발송 지연을 `1500ms`로 두고 동기 발송과 비동기 접수를 각각 실행하면 응답 시점의 차이를 볼 수 있다.

| 처음 실패할 횟수 | 최종 상태 | 처리 시도 |
| --- | --- | --- |
| 0 | SENT | 1회 |
| 2 | SENT | 3회 |
| 3 이상 | FAILED | 3회 |

비동기 접수 응답은 발송 완료를 뜻하지 않는다. 최종 결과는 목록이나 상태 조회 API로 확인한다.
재시도 간격은 최소 대기 시간이며, 다른 작업 처리와 폴링 주기에 따라 실제 간격은 더 길어질 수 있다.

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/notifications` | 알림 접수 |
| GET | `/api/notifications/{id}` | 알림 상태 조회 |
| GET | `/api/notifications` | 최근 알림 50개 조회 |
| GET | `/api/queue` | 미확인 작업과 최종 실패 기록 수 |
| POST | `/api/demo/send-sync` | 비교용 동기 발송, 목록에는 저장하지 않음 |
| GET | `/actuator/health` | 서버와 Redis 연결 확인 |

### 접수 요청

```json
{
  "recipient": "test-user",
  "message": "주문이 접수되었습니다.",
  "demoFailures": 2,
  "demoDelayMs": 1500
}
```

`demoFailures`는 처음 몇 번의 처리 시도를 실패시킬지, `demoDelayMs`는 각 시도에서 얼마나 기다릴지 정한다.

접수에 성공하면 알림 ID와 `QUEUED`를 반환한다. 응답의 `Location` 헤더에 있는 주소로 최신 상태를 조회할 수 있다.
상태는 `QUEUED → PROCESSING → SENT` 순서로 바뀌며, 실패하면 `RETRY_WAIT`를 거쳐 다시 시도하거나 `FAILED`로 끝난다.

`attempts`는 발송 직전에 올리는 처리 시도 수다. 중간에 서버가 종료됐다면 실제 발송 횟수와 다를 수 있다.
`/api/queue`의 pending 수는 소비자가 가져갔지만 아직 ACK하지 않은 작업만 센다. 아직 읽지 않은 새 작업은 포함하지 않는다.

## 코드 구성

```text
notification-server/
├── src/
│   ├── main/
│   │   ├── java/dev/riri/notification/  # 서버 코드
│   │   └── resources/
│   │       ├── application.yml        # 실행 설정
│   │       ├── redis/                 # 저장·완료 처리 스크립트
│   │       └── static/                # 테스트 화면
│   └── test/                         # 통합 테스트와 로컬 Redis
├── scripts/
│   ├── start-local.ps1
│   └── stop-local.ps1
├── .mvn/                             # Maven Wrapper 설정
├── compose.yaml
├── pom.xml
├── mvnw / mvnw.cmd
└── README.md
```

Java 코드는 한 패키지에 두었다. 요청은 컨트롤러에서 접수하고, 저장소에 기록한 작업을 워커가 가져가 발송한다.

| 클래스 | 역할 |
| --- | --- |
| `NotificationController` | 요청 검증, 접수와 조회 API, 오류 응답 |
| `NotificationStore` | Redis 저장과 조회 |
| `NotificationWorker` | 작업 소비, 발송, 재시도 |
| `DemoSender` | 발송 지연과 실패 재현 |

접수 상태 저장과 큐 등록은 `enqueue.lua`, 최종 결과와 실패 기록 및 ACK는 `finish.lua`에서 처리한다.
각 스크립트 안의 명령은 다른 Redis 명령과 섞이지 않고 실행된다. 다만 실행 중 오류가 발생했을 때 이전 쓰기를 롤백하지는 않으므로, 정해 둔 키 타입을 유지해야 한다.

Redis 연결 상태는 `QueueHealthIndicator`에서 PING으로 확인한다. Windows Redis의 INFO 응답에 포함된 경로 문자열을 변환할 때 발생한 오류 때문에 별도로 처리했다.

## 테스트

```powershell
.\mvnw.cmd test
```

별도 Redis 프로세스를 띄워 접수, ACK, 재시도, 실패 기록, 워커 재생성, 입력값 검증 등을 확인한다. 통합 테스트 8개를 작성했다.

## 남은 작업

현재는 애플리케이션 하나와 고정 소비자 `worker-1`을 기준으로 동작한다. 같은 Redis prefix로 서버를 여러 대 띄우는 구성은 지원하지 않는다.

- 접수 API에 멱등성 키 적용
- 실제 발송사 연동과 중복 발송 처리
- 여러 워커 사이의 작업 소유권 이전
- 상태와 Stream의 보관 기간 설정
- 인증과 부하 테스트

특히 외부 발송이 성공한 직후 Redis에 결과를 기록하기 전에 서버가 종료되면, 재처리 과정에서 중복 발송될 수 있다. 실제 발송사를 연결할 때는 발송사의 멱등성 지원과 결과 조회 방법을 함께 확인해야 한다.

서버는 기본적으로 `127.0.0.1`에서만 요청을 받는다.
