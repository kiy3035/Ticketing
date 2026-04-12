# 인프라 구축 PLAN (ALB / RDS / EC2 앱 서버 2대 / GitHub Actions 연동)

이 문서는 **“지금은 안 했지만 앞으로 할 작업”**을 위한 상세 가이드다.  
실제 작업할 때 체크리스트처럼 하나씩 지워가면서 보면 된다.

---

## 1. 전체 목표 그림

- **App Server 2대 (t3.small)**  
  - 각 인스턴스에 Docker + Spring Boot 앱 컨테이너 실행
  - 헬스체크 엔드포인트: `/actuator/health`
- **Infra Server (t3a.medium)**  
  - Docker Compose: Redis, Kafka+Zookeeper, Prometheus, Grafana, Kafka UI, Redis Insight
- **Amazon RDS (MySQL)**  
  - 앱 서버 2대에서 공통 접속
- **ALB**  
  - 리스너: 80 or 443 → 타겟 그룹(두 앱 서버 8080)
  - 헬스체크: `/actuator/health`
- **k6 서버 (선택)**  
  - 별도 EC2에서 ALB 대상으로 부하 테스트
- **GitHub Actions**  
  - main 브랜치 push → 빌드 → 두 App Server에 배포

---

## 2. 공통 준비

- **VPC / 서브넷**
  - 기존 VPC를 사용하거나, `/16` CIDR로 새 VPC 생성
  - 퍼블릭 서브넷 2개 (ALB, k6 서버, bastion 용)
  - 프라이빗 서브넷 2개 (App Server, Infra Server, RDS 용)
  - 인터넷 게이트웨이 연결, 라우팅 설정

- **보안 그룹 설계**
  - `sg-alb`
    - Inbound: 80/443 (0.0.0.0/0 또는 회사 IP)
    - Outbound: 모두 허용
  - `sg-app`
    - Inbound: 8080 (from sg-alb, sg-bastion), 22 (from sg-bastion)
    - Outbound: 3306(RDS), 6379(Redis@infra), 9092(Kafka@infra), 9090(Prometheus) 등 허용
  - `sg-infra`
    - Inbound: 6379, 9092, 9090, 3000, 8081 등 (from sg-app, sg-bastion)
  - `sg-rds`
    - Inbound: 3306 (from sg-app, sg-infra, sg-bastion)
  - `sg-bastion` (선택)  
    - Inbound: 22 (내 IP)

---

## 3. RDS(MySQL) 설정 PLAN

1. **RDS 인스턴스 생성**
   - 엔진: MySQL 8.x
   - 스토리지: 20GB gp3 (시작점)
   - 인스턴스 클래스: `db.t3.small` 정도
   - VPC/서브넷 그룹: 위에서 만든 프라이빗 서브넷
   - 퍼블릭 접근: **No**
   - 보안 그룹: `sg-rds`
   - DB 이름: `ticketing`
   - 사용자: `ticketing_user`, 강한 비밀번호

2. **파라미터 / 옵션 그룹**
   - `time_zone = Asia/Seoul` (가능하면 파라미터 그룹에서 설정)
   - Connection timeout, max_connections 등은 추후 부하 테스트 후 조정

3. **애플리케이션 설정 연동**
   - `.env` (또는 AWS SSM Parameter Store)에 아래 변수 추가:
     - `DB_URL=jdbc:mysql://<RDS_ENDPOINT>:3306/ticketing?useSSL=false&serverTimezone=Asia/Seoul`
     - `DB_USERNAME=ticketing_user`
     - `DB_PASSWORD=...`
   - `application.properties`는 이미 `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`를 사용 중이므로 값만 바꿔주면 됨.

---

## 4. EC2 App Server 2대 PLAN (t3.small)

1. **인스턴스 생성**
   - 타입: `t3.small`
   - 개수: 2
   - AMI: Amazon Linux 2 or Ubuntu 22.04
   - 서브넷: 프라이빗 서브넷 (ALB 뒤에 위치)
   - 보안 그룹: `sg-app`

2. **필수 패키지 설치 (user-data 또는 수동)**
   - `yum update` 또는 `apt update`
   - `docker`, `docker-compose` 설치
   - Docker 서비스 enable + start

3. **애플리케이션 배포 디렉터리 구조**
   - `/opt/ticketing/`
     - `app.jar` 또는 `docker-compose.yml` (앱 컨테이너 하나만 있어도 됨)
     - `.env` (RDS/Redis/Kafka/Toss/메일/SMS 등 환경 변수)
   - 실행 스크립트 예시:
     - `run.sh`: `docker run --env-file .env -p 8080:8080 --name ticketing-app <image>`  
       또는 `java -jar app.jar`

4. **헬스체크 엔드포인트 확인**
   - `curl http://localhost:8080/actuator/health` 가 `{"status":"UP"}` 로 나오는지 확인

5. **로그**
   - `logs/ticketing.log`가 생성되는지, 디스크 사용량 모니터링 (CloudWatch Logs 연동은 추후)

---

## 5. Infra Server PLAN (t3a.medium, Docker Compose)

1. **인스턴스 생성**
   - 타입: `t3a.medium`
   - 서브넷: 프라이빗 서브넷
   - 보안 그룹: `sg-infra`

2. **Docker Compose 배포**
   - `/opt/infra/docker-compose.yml` 에 다음 서비스 포함:
     - `redis`: `--maxmemory 400mb --maxmemory-policy allkeys-lru --save ""`
     - `kafka` + `zookeeper`: 포트 9092/29092
     - `kafka-ui`: 8081
     - `redisinsight`: 5540
     - `prometheus`: 9090
     - `grafana`: 3000
   - `docker compose up -d`

3. **네트워크 / DNS**
   - App Server에서는 Redis/Kafka/Prometheus를 **Infra Server의 프라이빗 IP** 또는 프라이빗 DNS로 접근
   - `.env` 예:
     - `REDIS_HOST=<infra-private-ip>`
     - `KAFKA_BOOTSTRAP_SERVERS=<infra-private-ip>:29092`

---

## 6. ALB 설정 PLAN

1. **타겟 그룹 생성**
   - 타입: Instance or IP (Instance 추천)
   - 포트: 8080
   - 프로토콜: HTTP
   - 헬스체크:
     - Protocol: HTTP
     - Path: `/actuator/health`
     - Healthy threshold / Interval: 기본값 사용, 필요 시 조정
   - 타겟: App Server 2대 등록

2. **ALB 생성**
   - 리스너:
     - HTTP 80 → 위 타겟 그룹
     - (추후 HTTPS 443 + ACM 인증서 붙이기 PLAN)
   - 서브넷: 퍼블릭 서브넷 2개
   - 보안 그룹: `sg-alb`

3. **Sticky Session (SSE용)**
   - SSE 연결을 고려하면, ALB Target Group에서 **Stickiness** 활성화:
     - Type: Load balancer generated cookie
     - Duration: 예) 1800초
   - 이렇게 하면 `SseNotificationService`가 인스턴스 로컬의 `ConcurrentHashMap`으로 연결을 들고 있어도, 같은 사용자는 같은 인스턴스로 계속 라우팅됨.

---

## 7. GitHub Actions → EC2 배포 PLAN

현재 `.github/workflows/deploy-prod.yml`만 있는 상태에서, 다음을 보완한다.

1. **EC2 접속 방식 결정**
   - 방법 A: SSH + scp (현재 방식 유지)
   - 방법 B: SSM Session Manager 사용 (키 관리 단순화, 권장)

2. **배포 대상 서버 2대 처리**
   - `deploy-prod.yml`에서 `host: APP_SERVER_1` 과 `host: APP_SERVER_2` 두 번 실행
   - 또는 `matrix` 전략으로 서버 리스트를 돌게 구성:
     - `matrix: host: [APP_SERVER_1, APP_SERVER_2]`

3. **배포 단계**
   - `on: push: branches: [main]`
   - jobs:
     1. **build**:  
        - `./gradlew clean build -x test`  
        - 산출물: `build/libs/ticketing-*.jar`
     2. **deploy** (needs: build):  
        - 각 앱 서버에 `scp`로 jar 또는 Docker image tag 전달  
        - 서버에서 이전 컨테이너/프로세스 중지  
        - 새 버전 실행 (`docker compose pull && docker compose up -d` 또는 `java -jar` 재시작)

4. **환경 변수 전달**
   - GitHub Secrets:
     - `PROD_SSH_KEY`, `APP_SERVER_1_HOST`, `APP_SERVER_2_HOST`, `DB_PASSWORD`, `TOSS_SECRET_KEY`, `MAIL_PASSWORD`, `SOLAPI_API_SECRET` 등
   - 서버 측 `.env`에는 DB/Redis/Kafka/RDS 엔드포인트와 함께 민감 값은 GitHub Actions에서 SSH로 들어가 `env` 파일을 생성하는 방식으로 관리 (또는 수동 배포 초기에만 만들고 이후에는 그대로 사용).

---

## 8. k6 서버 PLAN (선택, 성능 측정 고도화)

1. **별도 EC2 생성**
   - 타입: t3.small 정도
   - 퍼블릭 서브넷 + 보안 그룹: HTTP/HTTPS 아웃바운드 허용

2. **k6 설치**
   - `curl -s https://packagecloud.io/install/repositories/loadimpact/k6/script.deb.sh | sudo bash`  
     또는 공식 설치 가이드

3. **테스트 대상**
   - `BASE_URL`을 **ALB DNS**로 설정 (예: `http://<alb-dns-name>`)
   - `CONCERT_ID`, `TEST_USER`, `TEST_PASS` 등을 환경 변수로 주입

4. **결과 기록**
   - `docs/load-test-portfolio.md` 템플릿에 VU / RPS / p95 / 에러율 / knee point 기록
   - Prometheus/Grafana 스크린샷과 함께 포트폴리오에 활용

---

## 9. 체크리스트 요약

- [ ] VPC / 서브넷 / 보안 그룹 설계 완료
- [ ] RDS 인스턴스 생성 및 앱에서 접속 테스트
- [ ] Infra Server(t3a.medium) + Docker Compose로 Redis/Kafka/Prometheus/Grafana 구성
- [ ] App Server 2대(t3.small) 생성, Docker/Java 설치, `/actuator/health` 동작 확인
- [ ] ALB + Target Group 구성, 헬스체크 및 Sticky Session 설정
- [ ] GitHub Actions에서 두 App Server로 배포 자동화
- [ ] (선택) k6 전용 서버 생성 및 ALB 대상 부하 테스트 실행
- [ ] `docs/architecture.md`, `docs/infra.md`, `docs/load-test-portfolio.md`에 실제 구성/수치 반영
