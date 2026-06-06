"""
AI 분석 실측 테스트 — analyzer가 실제 Claude API를 호출해 분석을 생성하는지 확인.

키는 화면에 노출하지 않는다. 아래 우선순위로 ANTHROPIC_API_KEY를 읽는다:
  1) 이미 설정된 환경변수
  2) loadtest-harness/secrets.env 파일의 ANTHROPIC_API_KEY=... 줄 (gitignore됨)

실행: python samples/ai_live_check.py
(파일명을 test_* 로 두지 말 것 — pytest가 테스트로 수집해 CI가 깨진다)
"""
import os
import sys
import time
from pathlib import Path

HARNESS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(HARNESS_DIR))

# secrets.env 로드 (환경변수에 없을 때만)
if not os.environ.get("ANTHROPIC_API_KEY"):
    secrets = HARNESS_DIR / "secrets.env"
    if secrets.exists():
        for line in secrets.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))

if not os.environ.get("ANTHROPIC_API_KEY"):
    print("❌ ANTHROPIC_API_KEY 없음. loadtest-harness/secrets.env 에 키를 넣거나 환경변수로 설정하세요.")
    sys.exit(1)

import yaml  # noqa: E402
from k6_runner import RunResult  # noqa: E402
import analyzer  # noqa: E402

# config의 analyzer 설정(모델 등) 사용 — 실제 모델 id 유효성도 함께 검증됨
cfg = yaml.safe_load((HARNESS_DIR / "config.yaml").read_text(encoding="utf-8"))["analyzer"]
cfg["enabled"] = True

# 합성 데이터 (knee point처럼 부하 누적 시 p95·실패율 상승)
runs = [
    RunResult("knee-point", 1, time.time() - 300, time.time() - 5,
              {"http_reqs_rate": 820.5, "http_req_duration_p95": 95.2, "http_req_duration_max": 410.0,
               "http_req_failed_rate": 0.0, "http_reqs_count": 49230, "vus_max": 1500}, "", 0),
    RunResult("knee-point", 2, time.time() - 300, time.time() - 5,
              {"http_reqs_rate": 810.1, "http_req_duration_p95": 102.7, "http_req_duration_max": 520.0,
               "http_req_failed_rate": 0.0102, "http_reqs_count": 48610, "vus_max": 1500}, "", 0),
    RunResult("knee-point", 3, time.time() - 300, time.time() - 5,
              {"http_reqs_rate": 805.9, "http_req_duration_p95": 110.3, "http_req_duration_max": 600.0,
               "http_req_failed_rate": 0.0341, "http_reqs_count": 48300, "vus_max": 1500}, "", 0),
]
prom = [
    {"rps": {"max": 800.0, "mean": 700.0}, "hikari_pending": {"max": 1, "mean": 0}},
    {"rps": {"max": 950.0, "mean": 820.0}, "hikari_pending": {"max": 5, "mean": 2}},
    {"rps": {"max": 980.0, "mean": 840.0}, "hikari_pending": {"max": 11, "mean": 4}},
]

print(f"▶ 모델: {cfg['model']} — Claude API 호출 중...\n")
result = analyzer.analyze("knee-point", runs, prom, cfg)
print("===== AI 분석 결과 =====\n")
print(result)
