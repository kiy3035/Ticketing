# Java·운영 한 페이지 (포트폴리오 메모)

## Actuator Health: `ticketingDatastores`

- **경로**: `GET /actuator/health` 응답에 `components.ticketingDatastores` 로 노출된다.
- **의미**: Redis `PING` 과 JDBC `Connection#isValid(2)` 를 각각 수행한다. 둘 다 성공할 때만 이 컴포넌트는 `UP` 이다. 하나라도 실패하면 `DOWN` 이므로, 쿠버네티스 readiness 등에 “DB+Redis 코어” 기준으로 쓸 수 있다.
- **참고**: 전역 설정에서 `management.health.kafka.enabled=false` 로 두었으므로, 브로커 장애가 기본 liveness/readiness 전체를 타임아웃으로 죽이지 않게 한다. Kafka 가용성은 별도 모니터링·DLT 정책으로 다룬다.

## 부하 테스트 후 JVM·프로파일링 (권장 절차)

1. **GC 로그**: 예) `-Xlog:gc*:file=gc.log:time,uptime,level,tags` (JDK 11+) 로 한 번 캡처해 “할당 속도·힙 성장·Major GC 간격”을 스크린샷이나 한 단락으로 정리한다.
2. **스레드 덤프**: 부하 직후 `jcmd <pid> Thread.print` 또는 VisualVM으로 “락 대기·풀 고갈”이 있는지 확인한다. Virtual Thread 사용 시 플랫폼 스레드 수와 carrier 스레드 관계를 같이 적어 두면 설명이 쉽다.
3. **async-profiler**: CPU 샘플 한 번(`-e cpu`)으로 “핫 스팟이 DB/Redis/Kafka/직렬화 중 어디인지”만 요약해도 포트폴리오에서 운영 역량으로 읽힌다.

위 세 가지를 모두 할 필요는 없고, **한 번의 부하 테스트와 한 가지 도구 결과**만 README나 이 문서에 링크·요약해 두어도 충분하다.
