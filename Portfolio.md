# 콘서트 예매 시스템 — 포트폴리오 요약

> 이 파일은 **메인 포트폴리오 문서로 갈음**합니다.

## 👉 메인 문서

- **백엔드 포트폴리오 (메인)**: [`docs/ticketing-portfolio.md`](docs/ticketing-portfolio.md)
  - 아키텍처, k6 부하 테스트 트러블슈팅 4사례(DB 병목·Knee Point·페일오버·분산락 정확성 증명), 구현 결정 2건(Saga 보상·JWT), ADR 6개
- **부하 테스트 (Phase 1~8)**: [`docs/load-test-portfolio.md`](docs/load-test-portfolio.md)
  - 캐시 도입으로 p95 78% 감소, 2대 분산으로 1,447 RPS, knee point VU=1,000~1,200, nginx 페일오버 검증
- **JWT 인증 설계**: [`docs/jwt-auth.md`](docs/jwt-auth.md)
- **시퀀스 다이어그램**: [`docs/sequence-diagrams.md`](docs/sequence-diagrams.md)
- **부록**: [`docs/data.md`](docs/data.md), [`docs/infra.md`](docs/infra.md), [`docs/monitoring.md`](docs/monitoring.md), [`docs/deployment-ec2.md`](docs/deployment-ec2.md)

## 면접 가이드

- 예상 Q&A: [`interview/`](interview/) — 9개 영역(아키텍처/동시성/Redis/대기열/트랜잭션/인프라/테스트/JWT) 별 난이도 표시 답변
- 테스트 산출물: [`test-code/`](test-code/) — 16개 테스트 클래스, 46개 메서드, evidence 스크린샷
