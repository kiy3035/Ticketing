# 부하 테스트 실행 가이드 (뼈대)

**용량 검증의 목적·범위·판정·런북**은 `docs/load-test-portfolio.md`에 모아 두었다. 이 파일은 **명령어만** 빠르게 보기 위한 것이다.

## 사전 체크

- [ ] `BASE_URL`, `CONCERT_ID` 고정
- [ ] `db-read.js`용 테스트 계정·데이터 준비 (`TEST_USER` / `TEST_PASS`)
- [ ] Grafana 시간축이 부하 구간과 겹치도록 설정
- [ ] Hikari pool / JVM 변경은 **한 축씩** 적용 후 재기동

(상세 SQL·데이터 시드는 필요 시 별도 백업·문서를 사용한다.)

## k6 설치·스크립트 배포

원격 k6 인스턴스에 `load-tests/` 디렉터리를 올린 뒤, `load-tests/README.md`의 공통 env를 참고한다.

## 오늘 사용하는 스크립트 (2개)

### `queue-flow.js` (인증 없음)

```bash
k6 run -e BASE_URL=http://<app>:8080 -e CONCERT_ID=<id> load-tests/queue-flow.js
```

### `db-read.js` (로그인 필요)

```bash
k6 run -e BASE_URL=http://<app>:8080 -e CONCERT_ID=<id> \
  -e TEST_USER=<user> -e TEST_PASS=<pass> \
  load-tests/db-read.js
```

Knee 탐색 시: `-e K6_PROFILE=stress`, `-e K6_PEAK_VU=...` 등은 `lib/stages.js`·`README.md` 참고.

## 결과 해석 (요약)

- k6 요약의 `http_req_duration` p(95), `http_req_failed`
- Grafana 6패널: `docs/load-test-portfolio.md` 표와 동일 PromQL
