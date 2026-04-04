# 부하 테스트 실행 가이드

## 사전 준비

### 1. 테스트 데이터 생성
서버에 테스트용 계정과 콘서트 데이터를 미리 넣어야 함:

```sql
-- 테스트 계정 200개 생성 (비밀번호: test1234 → BCrypt 해시)
INSERT INTO users (username, pw, email, role, point, noti_type, created_at, updated_at)
SELECT 
    CONCAT('loadtest', seq),
    '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
    CONCAT('loadtest', seq, '@test.com'),
    'USER',
    1000000,
    'email',
    NOW(),
    NOW()
FROM (
    SELECT @row := @row + 1 AS seq
    FROM information_schema.tables t1,
         information_schema.tables t2,
         (SELECT @row := -1) r
    LIMIT 200
) numbers;

-- 테스트 콘서트 (좌석 1000개)
INSERT INTO concert (title, venue, concert_at, status, category, created_at, updated_at)
VALUES ('부하테스트 콘서트', '테스트홀', DATE_ADD(NOW(), INTERVAL 30 DAY), 'UPCOMING', 'BAND', NOW(), NOW());

-- 좌석 1000개 생성
INSERT INTO seat (concert_id, section, seat_no, price, status, created_at, updated_at)
SELECT 
    (SELECT id FROM concert WHERE title = '부하테스트 콘서트'),
    CASE WHEN seq <= 250 THEN 'VIP' WHEN seq <= 500 THEN 'A' WHEN seq <= 750 THEN 'B' ELSE 'C' END,
    seq,
    CASE WHEN seq <= 250 THEN 150000 WHEN seq <= 500 THEN 100000 WHEN seq <= 750 THEN 70000 ELSE 50000 END,
    'AVAILABLE',
    NOW(),
    NOW()
FROM (
    SELECT @row2 := @row2 + 1 AS seq
    FROM information_schema.tables t1,
         information_schema.tables t2,
         (SELECT @row2 := 0) r
    LIMIT 1000
) numbers;
```

### 2. k6 설치 (EC2 t3a.small)
```bash
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
    --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D68
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update && sudo apt-get install k6
```

### 3. k6 스크립트 업로드
```bash
scp -r load-tests/ ec2-user@k6-server:~/load-tests/
```

## 실행

모든 스크립트는 `load-tests/` 폴더에 있다. 환경변수로 VU/시간/프로필을 코드 수정 없이 조절 가능.
자세한 시나리오별 설명은 `load-tests/README.md` 참고.

### 대기열 테스트 (인증 불필요, 가장 가벼움)
```bash
k6 run -e BASE_URL=http://app-server:8080 \
       -e CONCERT_ID=1 \
       load-tests/queue-flow.js
```

### 좌석 홀드 테스트 (인증 필요)
```bash
k6 run -e BASE_URL=http://app-server:8080 \
       -e CONCERT_ID=1 \
       -e TEST_USER=loadtest0 -e TEST_PASS=test1234 \
       load-tests/seats-hold.js
```

### E2E 예매 테스트 (인증 필요)
```bash
k6 run -e BASE_URL=http://app-server:8080 \
       -e CONCERT_ID=1 \
       -e TEST_USER=loadtest0 -e TEST_PASS=test1234 \
       load-tests/full-flow.js
```

### Knee Point 탐색 (stress 프로필)
```bash
k6 run -e BASE_URL=http://app-server:8080 \
       -e CONCERT_ID=1 \
       -e K6_PEAK_VU=500 -e K6_PROFILE=stress \
       load-tests/full-flow.js
```

## 결과 분석 방법

### k6 출력 읽기
```
http_req_duration..............: avg=123ms  min=5ms  med=89ms  max=2345ms  p(90)=234ms  p(95)=456ms
http_req_failed................: 2.34% ✓ 23  ✗ 960
iterations.....................: 983   16.38/s
vus............................: 100   min=0   max=100
```

- `http_req_duration p(95)`: 95%의 요청이 이 시간 이내에 완료됨
- `http_req_failed`: 에러율 (5% 이내가 목표)
- `iterations/s`: 초당 처리량 (TPS)

### Knee Point 찾기
`K6_PEAK_VU`를 올려가면서 실행 (`K6_PROFILE=stress`로 에러 나도 끝까지 진행):
```bash
k6 run -e K6_PEAK_VU=100 -e K6_PROFILE=stress -e BASE_URL=... load-tests/full-flow.js
k6 run -e K6_PEAK_VU=200 -e K6_PROFILE=stress -e BASE_URL=... load-tests/full-flow.js
k6 run -e K6_PEAK_VU=300 -e K6_PROFILE=stress -e BASE_URL=... load-tests/full-flow.js
```

p95 응답시간이 급격히 올라가는 지점 = Knee Point = 시스템 한계

### Grafana 대시보드 확인
부하 테스트 중 Grafana에서 실시간 확인할 것:
- CPU / 메모리 사용량
- Redis 연결 수 / 명령 처리량
- JVM Heap / GC 빈도
- Kafka Consumer Lag
- `ticketing_active_holds` (활성 홀드 수)
- `ticketing_payment_complete_duration_seconds` (결제 소요 시간)
