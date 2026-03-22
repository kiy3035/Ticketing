# EC2 앱 수평 확장 (1대 → 2대 + ALB)

앱은 **상태를 Redis(세션)·MySQL·Kafka**에 두는 구조라 **동일 이미지를 EC2 여러 대**에 올리고 **ALB**로 나누는 전개가 가능하다.

## 지금 레포에서 맞춰 둔 것

- **`application-prod.properties`**: ALB 뒤에서 올바른 URL·스킴을 쓰도록 `server.forward-headers-strategy=framework`, 롤링 종료용 `server.shutdown=graceful`, 운영용 정적 리소스·로그·메트릭 태그.
- **`docker-compose.app.yml` / `Dockerfile.runtime`**: EC2 배포 시 `ticketing-app.jar` 로 이미지 빌드. 소스만으로 빌드할 때는 멀티 스테이지 **`Dockerfile`**. `prod` 푸시 시 GitHub Actions가 JAR·`Dockerfile.runtime`·compose 를 scp 후 `docker compose up -d --build`.
- **메트릭**: 인스턴스 구분을 위해 EC2마다 `INSTANCE_ID`(또는 컨테이너 `HOSTNAME`)를 다르게 두면 Grafana에서 구분하기 쉽다.

## 2대로 늘릴 때 체크리스트

1. **동일 아티팩트**: 두 EC2 모두 **같은 Docker 이미지(또는 같은 JAR)** + **같은 DB/Redis/Kafka/RDS 주소** (`.env` 동일).
2. **보안 그룹**: ALB는 80/443(또는 8080) 수신, **타깃 그룹**은 앱 인스턴스의 8080. 앱 SG는 **ALB SG에서만** 앱 포트 허용 권장.
3. **OAuth(Google)**: 승인된 리디렉션 URI에 **ALB DNS(또는 도메인)** 기준 `https://.../login/oauth2/code/google` 등록. IP 직접 접속은 제거하거나 테스트용만 유지.
4. **SSE 등 장시간 연결**: 클라이언트가 특정 인스턴스에 붙어야 하면 ALB에서 **Sticky session(쿠키 기반)** 검토. 세션은 Redis라 일반 요청은 스티키 없이도 가능한 경우가 많다.
5. **Kafka 소비자**: 동일 `group-id`로 인스턴스가 늘면 **파티션 수**가 소비자 수보다 작으면 일부 인스턴스가 토픽을 소비하지 못할 수 있다. 부하에 맞게 토픽 파티션을 늘린다.
6. **스케줄러/배치**: 동일 코드가 2대에서 돌면 `@Scheduled` 가 **중복 실행**될 수 있다. 필요 시 ShedLock·리더 선출·한 인스턴스만 실행 등으로 보강한다.

## 단일 EC2일 때

위 설정은 **1대**에서도 그대로 사용 가능하다. ALB 없이 붙이면 `forward-headers` 는 불필요한 경우가 많지만, **해를 거의 주지 않는다**.
