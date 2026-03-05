# 인프라 & 환경 설정

## 환경 변수 (.env)

프로젝트 루트의 `.env` 파일로 환경 변수를 관리합니다. (`.gitignore` 포함)

### MySQL 설정
```env
DB_URL=jdbc:mysql://localhost:3306/ticketing?useSSL=false&serverTimezone=UTC
DB_USERNAME=root
DB_PASSWORD=your_password
```

### Redis 설정
```env
REDIS_HOST=localhost
REDIS_PORT=6379
```

### Kafka 설정
```env
KAFKA_BOOTSTRAP_SERVERS=localhost:29092
KAFKA_CONSUMER_GROUP=ticketing-notification
```

## 애플리케이션 설정 (application.properties)

### 데이터베이스 설정
```properties
# MySQL
spring.datasource.url=${DB_URL:jdbc:mysql://localhost:3306/ticketing?useSSL=false&serverTimezone=UTC}
spring.datasource.username=${DB_USERNAME:root}
spring.datasource.password=${DB_PASSWORD:}
spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.jdbc.time_zone=Asia/Seoul
```

**설명**:
- `ddl-auto=update`: 엔티티 변경 시 자동으로 스키마 업데이트 (개발 환경용)
- `time_zone=Asia/Seoul`: 한국 시간대 설정

### Redis 설정
```properties
# Redis 연결
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}

# Redis 연결 풀 설정
spring.data.redis.lettuce.pool.max-active=20      # 최대 활성 연결 수
spring.data.redis.lettuce.pool.max-idle=10        # 최대 유휴 연결 수
spring.data.redis.lettuce.pool.min-idle=5         # 최소 유휴 연결 수
spring.data.redis.lettuce.pool.max-wait=2000ms    # 연결 대기 시간
spring.data.redis.timeout=2000ms                   # 명령어 타임아웃
```

**설명**:
- **Lettuce**: 비동기 Redis 클라이언트
- **연결 풀링**: `commons-pool2`로 연결 관리
- **타임아웃**: 2초로 설정하여 응답 지연 방지

### 세션 설정
```properties
# 세션 (Redis)
spring.session.store-type=redis
spring.session.redis.namespace=ticketing:sessions
server.servlet.session.timeout=30m
```

**설명**:
- **저장소**: Redis에 세션 저장
- **네임스페이스**: `ticketing:sessions`로 키 구분
- **만료 시간**: 30분 비활성 시 세션 만료

### Kafka 설정
```properties
# Kafka 브로커
spring.kafka.bootstrap-servers=${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

# Producer 설정
spring.kafka.producer.acks=all                    # 모든 리플리카 확인
spring.kafka.producer.retries=3                   # 재시도 횟수
spring.kafka.producer.properties.enable.idempotence=true  # 멱등성 보장

# Consumer 설정
spring.kafka.consumer.group-id=${KAFKA_CONSUMER_GROUP:ticketing-notification}
spring.kafka.consumer.auto-offset-reset=latest    # 최신 오프셋부터 시작
```

**설명**:
- **acks=all**: 모든 리플리카 확인으로 데이터 손실 방지
- **idempotence**: 중복 메시지 방지
- **auto-offset-reset=latest**: Consumer Group이 없을 때 최신 메시지부터 시작

### 캐시 설정
```properties
# 캐시 타입
spring.cache.type=redis
logging.level.org.springframework.cache=DEBUG
```

**설명**:
- **캐시 타입**: Redis를 캐시 저장소로 사용
- **로깅**: 캐시 동작 디버깅용

### 취소된 공연 환불 배치 설정
```properties
# 취소된 공연 환불 배치
ticketing.refund.batch-size=50                    # 한 번에 처리할 결제 건수
ticketing.refund.interval-ms=300000               # 배치 실행 주기 (5분)
```

**설명**:
- **batch-size**: 콘서트별 COMPLETED 결제를 한 번에 조회·처리할 건수
- **interval-ms**: 스케줄러 실행 주기 (기본 5분)

### 대기열 설정
```properties
# 대기열 설정
ticketing.queue.batch-size=50                     # 한 번에 처리할 사용자 수
ticketing.queue.processing-interval-ms=2000        # 스케줄러 실행 주기 (밀리초)
ticketing.queue.token-ttl-seconds=60            # 대기열 토큰 TTL (초, 1분)
ticketing.queue.cleanup-interval-ms=60000          # 만료 토큰 정리 주기 (밀리초)
ticketing.queue.cleanup-batch-size=200            # 한 번에 정리할 토큰 수
```

**설명**:
- **batch-size**: 스케줄러가 한 번에 처리할 사용자 수 (서버 부하 조절)
- **processing-interval-ms**: 대기열 처리 주기 (2초)
- **token-ttl-seconds**: 토큰 자동 만료 시간 (30분)
- **cleanup-interval-ms**: 만료 토큰 정리 주기 (기본 60초)
- **cleanup-batch-size**: 정리 시 스캔할 토큰 수

### 홀드 설정
```properties
# 좌석 홀드 설정
ticketing.hold.ttl-seconds=300                    # 홀드 TTL (초, 5분)
ticketing.hold.cleanup-interval-ms=60000          # 홀드 정리 스케줄러 주기 (밀리초)
ticketing.kafka.hold-topic=ticketing.seat-hold-events  # Kafka 토픽 이름
```

**설명**:
- **ttl-seconds**: 홀드 유지 시간 (5분)
- **cleanup-interval-ms**: 만료 홀드 정리 주기 (60초)
- **hold-topic**: Kafka 이벤트 토픽 이름

### 로깅 설정
```properties
# 보안/에러 로그 파일 기록
logging.level.org.springframework.security=DEBUG
logging.file.name=logs/ticketing.log
```

**설명**:
- **보안 로그**: 인증/인가 관련 로그 디버깅
- **파일 로그**: `logs/ticketing.log`에 로그 저장

### 정적 리소스 설정
```properties
# 정적 리소스 캐시 비활성화(개발 편의)
spring.web.resources.cache.cachecontrol.no-store=true
spring.web.resources.cache.cachecontrol.max-age=0
spring.web.resources.chain.cache=false

# 정적 리소스를 파일 시스템에서 직접 로드(재시작 없이 반영)
spring.web.resources.static-locations=classpath:/static/,file:${user.dir}/src/main/resources/static/
```

**설명**:
- **캐시 비활성화**: 개발 중 파일 변경 즉시 반영
- **파일 시스템 로드**: 재시작 없이 정적 리소스 변경 반영

## Docker Compose 설정

### 전체 구성
```yaml
version: "3.8"

services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.1
    container_name: zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"
    deploy:
      resources:
        limits:
          memory: 256M
        reservations:
          memory: 128M

  kafka:
    image: confluentinc/cp-kafka:7.6.1
    container_name: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
      - "29092:29092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,PLAINTEXT_HOST://0.0.0.0:29092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    deploy:
      resources:
        limits:
          memory: 512M
        reservations:
          memory: 256M

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: kafka-ui
    depends_on:
      - kafka
    ports:
      - "8081:8080"
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
    deploy:
      resources:
        limits:
          memory: 256M

  redis:
    image: redis:7.2-alpine
    container_name: redis
    ports:
      - "6379:6379"
    deploy:
      resources:
        limits:
          memory: 512M
        reservations:
          memory: 256M
    command: redis-server --maxmemory 400mb --maxmemory-policy allkeys-lru --save ""

  redisinsight:
    image: redis/redisinsight:latest
    container_name: redisinsight
    ports:
      - "5540:5540"
    depends_on:
      - redis
    deploy:
      resources:
        limits:
          memory: 256M
```

### 서비스 설명

#### Zookeeper
- **역할**: Kafka의 메타데이터 관리
- **포트**: 2181
- **메모리**: 최대 256MB

#### Kafka
- **역할**: 이벤트 스트리밍 플랫폼
- **포트**: 
  - 9092: 내부 통신
  - 29092: 호스트 통신
- **메모리**: 최대 512MB
- **설정**: 단일 브로커, 리플리케이션 팩터 1 (로컬 개발용)

#### Kafka UI
- **역할**: Kafka 관리 UI
- **포트**: 8081
- **접속**: http://localhost:8081

#### Redis
- **역할**: 세션/홀드/락/캐시/대기열 저장소
- **포트**: 6379
- **메모리**: 최대 512MB (실제 사용 400MB)
- **정책**: `allkeys-lru` (LRU 기반 eviction)
- **Persistence**: 비활성화 (`--save ""`) - 개발 환경용

#### Redis Insight
- **역할**: Redis 관리 UI
- **포트**: 5540
- **접속**: http://localhost:5540

### 실행 방법
```bash
# 서비스 시작
docker compose up -d

# 서비스 중지
docker compose down

# 로그 확인
docker compose logs -f redis
docker compose logs -f kafka

# 서비스 상태 확인
docker compose ps
```

## JVM 메모리 설정

### Gradle 빌드 설정 (build.gradle)
```gradle
bootRun {
    jvmArgs = ['-Xmx2g', '-Xms1g']
}
```

**설명**:
- **-Xmx2g**: 최대 힙 메모리 2GB
- **-Xms1g**: 초기 힙 메모리 1GB
- **목적**: 대규모 트래픽 처리 시 메모리 확보

### 실행 시 메모리 확인
```bash
# JVM 메모리 정보 출력
java -XX:+PrintFlagsFinal -version | grep -iE 'HeapSize|PermSize|MetaspaceSize'
```

## 성능 튜닝 설정

### Redis 연결 풀 최적화
```properties
spring.data.redis.lettuce.pool.max-active=20      # 최대 활성 연결
spring.data.redis.lettuce.pool.max-idle=10        # 최대 유휴 연결
spring.data.redis.lettuce.pool.min-idle=5         # 최소 유휴 연결
spring.data.redis.lettuce.pool.max-wait=2000ms    # 연결 대기 시간
```

**튜닝 가이드**:
- **max-active**: 동시 요청 수에 따라 조정 (기본 20)
- **max-idle**: 유지할 유휴 연결 수 (기본 10)
- **min-idle**: 최소 유지 연결 수 (기본 5)
- **max-wait**: 연결 대기 시간 (기본 2초)

### Kafka Producer 최적화
```properties
spring.kafka.producer.acks=all                    # 모든 리플리카 확인
spring.kafka.producer.retries=3                   # 재시도 횟수
spring.kafka.producer.properties.enable.idempotence=true  # 멱등성
```

**튜닝 가이드**:
- **acks=all**: 데이터 손실 방지 (성능 vs 안정성)
- **retries**: 네트워크 오류 시 재시도
- **idempotence**: 중복 메시지 방지

### 스케줄러 주기 최적화
```properties
# 대기열 처리 주기
ticketing.queue.processing-interval-ms=2000        # 2초 (빠른 응답)

# 홀드 만료 처리 주기
ticketing.hold.cleanup-interval-ms=60000          # 60초 (적절한 주기)
```

**튜닝 가이드**:
- **대기열 처리**: 짧을수록 빠른 응답, 하지만 서버 부하 증가
- **홀드 만료**: 길수록 서버 부하 감소, 하지만 만료 지연

## 모니터링 도구

### Kafka UI
- **URL**: http://localhost:8081
- **기능**:
  - 토픽 목록 조회
  - 메시지 조회
  - Consumer Group 상태 확인
  - 오프셋 확인

### Redis Insight
- **URL**: http://localhost:5540
- **기능**:
  - 키 조회 및 수정
  - 메모리 사용량 확인
  - 명령어 실행
  - 연결 상태 확인

### 애플리케이션 로그
- **위치**: `logs/ticketing.log`
- **내용**:
  - 보안 로그 (인증/인가)
  - 에러 로그
  - 캐시 동작 로그

### 지표 API
- **엔드포인트**: `GET /api/metrics`
- **제공 정보**:
  - 실시간 접속자 수
  - 콘서트 수
  - 예약 수

## 배포 고려사항

### 환경별 설정
- **개발 환경**: `.env` 파일 사용
- **운영 환경**: 환경 변수 또는 설정 서버 사용

### 확장성
- **수평 확장**: Redis 세션으로 다중 인스턴스 지원
- **로드 밸런서**: Sticky Session 필요 (SSE 연결 관리)
- **Kafka**: Consumer Group으로 자동 분산 처리

### 보안
- **세션 쿠키**: HttpOnly, Secure 플래그 설정 권장
- **CORS**: 운영 환경에서 도메인 제한 필요
- **CSRF**: 운영 환경에서 활성화 권장

### 백업
- **MySQL**: 정기적인 DB 백업 필요
- **Redis**: Persistence 활성화 권장 (운영 환경)
- **Kafka**: 로그 보관 정책 설정

## 트러블슈팅

### Redis 연결 실패
```bash
# Redis 상태 확인
docker exec redis redis-cli ping

# 연결 테스트
redis-cli -h localhost -p 6379 ping
```

### Kafka 연결 실패
```bash
# Kafka 상태 확인
docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092

# 토픽 목록 확인
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092
```

### 메모리 부족
```bash
# 컨테이너 메모리 사용량 확인
docker stats

# Redis 메모리 사용량 확인
docker exec redis redis-cli INFO memory
```

### 세션 만료 문제
- **원인**: Redis TTL 설정 확인
- **해결**: `server.servlet.session.timeout` 값 확인
