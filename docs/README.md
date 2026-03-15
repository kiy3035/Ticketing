# 문서 목차 (포트폴리오 / 면접관용)

이 폴더는 **외부 공유·면접 시 참고**용 문서입니다. 프로젝트 구조, API, 아키텍처, 인프라를 요약해 두었습니다.

## 📚 문서 목록

| 문서 | 설명 |
|------|------|
| [architecture.md](architecture.md) | 시스템 아키텍처, 레이어 구성, **핵심 플로우**(대기열·홀드·예약 확정·환불 배치) 시퀀스 다이어그램 |
| [api.md](api.md) | REST API 명세 (인증, 공연, 대기열, 홀드, 예약, 결제, 알림, 관리자·판매자) |
| [concurrency.md](concurrency.md) | 동시성 설계: Redis 분산 락, 좌석 락 키 형식, 재시도 설정 |
| [data.md](data.md) | Redis 키 구조, Kafka 토픽·이벤트, 세션·캐시, 홀드 만료 처리 |
| [infra.md](infra.md) | 인프라 구성(Docker, MySQL, Redis, Kafka), 스케줄러 주기·배치 크기 설정 가이드 |
| [deployment-ec2.md](deployment-ec2.md) | AWS EC2 배포 방법, 환경 변수, 헬스체크 |
| [load-test-results.md](load-test-results.md) | k6 부하 테스트 결과, knee point 등 (작성 시) |
| [monitoring.md](monitoring.md) | Prometheus/Grafana, Actuator, 로그 설정 |
| [admin-setup.md](admin-setup.md) | 관리자 계정·역할 설정 |
| [payment-method-and-toss.md](payment-method-and-toss.md) | 결제 수단(POINT/CARD), 토스 주문서형 위젯·연동 요약 |
| [debounce-throttle.md](debounce-throttle.md) | 프론트 검색 디바운스·버튼 연타 방지 (요약) |

## 🗂 상세·공부용 문서

소스 코드 흐름, 전체 워크플로우, 클래스별 역할 등 **개인 공부·이해용** 정리는 **[my-docs/](../my-docs/)** 폴더에 따로 두었습니다.

- [my-docs/README.md](../my-docs/README.md) — my-docs 목적 및 목차
