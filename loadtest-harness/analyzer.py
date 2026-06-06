"""
분석기 — 수집된 k6/Prometheus 메트릭을 Claude API에 넘겨
knee point/bottleneck 관점의 진단을 생성한다.

중요(정직성): AI 분석은 '보조 진단'이다. 리포트에는 원본 메트릭 표를 항상 같이
실어 사람이 검증할 수 있게 한다. AI가 결론을 '결정'하는 게 아니라,
수집·해석·문서화라는 반복 작업을 거드는 역할.
"""
import logging
import os

log = logging.getLogger("harness.analyzer")

# 프롬프트: 프로젝트 핵심 과제(대용량 트래픽, 좌석 동시성)에 맞춘 분석 프레임 고정
_SYSTEM = """너는 백엔드 성능 엔지니어다. 티켓팅 시스템의 부하 테스트 결과를 분석한다.
반드시 knee point(처리량이 꺾이고 지연/에러가 급증하는 부하 지점)와
bottleneck(병목 자원: DB 커넥션 풀, Redis 락 경합, 스레드 등) 관점으로 진단하라.
근거 없는 단정을 피하고, 제시된 수치에서 읽히는 사실만 말하라.
출력은 한국어 마크다운으로, 아래 4개 섹션을 반드시 포함한다:
## Knee Point 판단
## 병목(Bottleneck) 진단
## 회차 간 일관성
## 다음 액션 제안"""


def _build_user_prompt(scenario: str, runs: list, prom_per_run: list) -> str:
    """k6 회차 메트릭 + Prometheus 윈도우 통계를 압축 텍스트로 직렬화."""
    lines = [f"# 시나리오: {scenario}", ""]
    lines.append("## k6 회차별 클라이언트 메트릭")
    lines.append("| 회차 | 평균RPS | p95(ms) | max(ms) | 실패율 | 총요청 | VUmax |")
    lines.append("|------|--------|---------|---------|--------|--------|-------|")
    for r in runs:
        s = r.summary
        lines.append("| {idx} | {rps} | {p95} | {mx} | {fail} | {cnt} | {vu} |".format(
            idx=r.run_index,
            rps=_n(s.get("http_reqs_rate")),
            p95=_n(s.get("http_req_duration_p95")),
            mx=_n(s.get("http_req_duration_max")),
            fail=_pct(s.get("http_req_failed_rate")),
            cnt=_n(s.get("http_reqs_count"), 0),
            vu=_n(s.get("vus_max"), 0),
        ))

    lines.append("")
    lines.append("## 서버측 Prometheus 메트릭 (회차별 윈도우 통계: max / mean)")
    for r, prom in zip(runs, prom_per_run):
        lines.append(f"### {r.run_index}회차")
        for name, stat in prom.items():
            lines.append(f"- {name}: max={_n(stat['max'])}, mean={_n(stat['mean'])}")
    return "\n".join(lines)


def analyze(scenario: str, runs: list, prom_per_run: list, cfg: dict) -> str:
    """Claude API 호출 → 분석 마크다운 반환. 비활성/키없음/오류 시 안내 문구 반환."""
    if not cfg.get("enabled", True):
        return "_AI 분석 비활성화됨 (config.analyzer.enabled=false)._"
    if not os.environ.get("ANTHROPIC_API_KEY"):
        log.warning("ANTHROPIC_API_KEY 미설정 — AI 분석을 건너뜀")
        return "_ANTHROPIC_API_KEY 미설정으로 AI 분석을 건너뜀. 원본 메트릭 표를 참고하세요._"

    try:
        from anthropic import Anthropic
    except ImportError:
        return "_anthropic 패키지 미설치 (pip install -r requirements.txt)._"

    user_prompt = _build_user_prompt(scenario, runs, prom_per_run)
    log.info("[%s] Claude API 분석 요청 (model=%s)", scenario, cfg["model"])
    try:
        client = Anthropic()
        resp = client.messages.create(
            model=cfg["model"],
            max_tokens=int(cfg.get("max_tokens", 2000)),
            system=_SYSTEM,
            messages=[{"role": "user", "content": user_prompt}],
        )
        return resp.content[0].text
    except Exception as e:  # API 오류로 전체 파이프라인이 죽지 않게
        log.error("[%s] Claude API 호출 실패: %s", scenario, e)
        return f"_AI 분석 실패: {e}. 원본 메트릭 표를 참고하세요._"


def _n(v, digits=2):
    if isinstance(v, (int, float)):
        return f"{v:.{digits}f}" if digits else f"{int(v)}"
    return "n/a"


def _pct(v):
    return f"{v * 100:.2f}%" if isinstance(v, (int, float)) else "n/a"
