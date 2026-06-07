"""
합성 예시 리포트 생성기 — 하네스 출력 '형식'을 보여주기 위한 용도.

⚠️ 여기 데이터는 전부 합성(가짜)이다. 실측이 아니다.
   실제 k6/Prometheus 없이도 리포트 모양을 확인·공유하기 위해 둔다.

실행: python samples/generate_sample.py
"""
import sys
import time
from pathlib import Path

# 부모 디렉토리(하네스 루트)를 import path에 추가
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import reporter  # noqa: E402
from k6_runner import RunResult  # noqa: E402

HARNESS_DIR = Path(__file__).resolve().parent.parent

# knee point처럼: 부하가 누적될수록 p95·실패율 상승 (합성)
_RUNS = [
    RunResult("knee-point", 1, time.time() - 300, time.time() - 5,
              {"http_reqs_rate": 820.5, "http_req_duration_p95": 95.2, "http_req_duration_max": 410.0,
               "http_req_failed_rate": 0.0, "http_reqs_count": 49230, "vus_max": 1500}, "_raw/sample1.json", 0),
    RunResult("knee-point", 2, time.time() - 300, time.time() - 5,
              {"http_reqs_rate": 810.1, "http_req_duration_p95": 102.7, "http_req_duration_max": 520.0,
               "http_req_failed_rate": 0.0102, "http_reqs_count": 48610, "vus_max": 1500}, "_raw/sample2.json", 0),
    RunResult("knee-point", 3, time.time() - 300, time.time() - 5,
              {"http_reqs_rate": 805.9, "http_req_duration_p95": 110.3, "http_req_duration_max": 600.0,
               "http_req_failed_rate": 0.0341, "http_reqs_count": 48300, "vus_max": 1500}, "_raw/sample3.json", 0),
]


def _fake_prom(rps_base, p95_base, pending_peak):
    t0 = time.time()
    n = 20
    return {
        "rps": {"series": [(t0 + i * 15, rps_base + min(i, 12) * 25) for i in range(n)],
                "max": rps_base + 300, "mean": rps_base + 180, "p95": rps_base + 290, "last": rps_base + 250},
        "latency_p95": {"series": [(t0 + i * 15, p95_base + (i ** 1.4) * 0.4) for i in range(n)],
                        "max": p95_base + 60, "mean": p95_base + 20, "p95": p95_base + 55, "last": p95_base + 58},
        "error_rate": {"series": [(t0 + i * 15, 0.0 if i < 12 else (i - 11) * 0.006) for i in range(n)],
                       "max": 0.034, "mean": 0.008, "p95": 0.03, "last": 0.034},
        "hikari_pending": {"series": [(t0 + i * 15, 0 if i < 10 else (i - 9)) for i in range(n)],
                           "max": pending_peak, "mean": pending_peak / 3, "p95": pending_peak, "last": pending_peak},
    }


_PROM = [_fake_prom(500, 40, 6), _fake_prom(500, 45, 9), _fake_prom(500, 48, 11)]

# AI 분석 자리 — 실제론 analyzer가 채우지만, 합성 예시에선 형식 안내 문구
_AI_PLACEHOLDER = (
    "_(예시) 실제 실행 시 이 자리에 Claude의 knee point/bottleneck 보조 분석이 채워집니다._\n\n"
    "## Knee Point 판단\nVU 1200 부근에서 RPS 증가가 둔화되고 p95가 급상승 — knee point 후보.\n\n"
    "## 병목(Bottleneck) 진단\nhikari_pending 동반 상승 → DB 커넥션 풀 포화가 1차 의심 지점.\n\n"
    "## 회차 간 일관성\n실패율 0.00→1.02→3.41%로 회차마다 증가 — 워밍업/캐시 상태 영향 가능.\n\n"
    "## 다음 액션 제안\n풀 사이즈 상향 후 동일 시나리오 재측정으로 knee point 이동 여부 확인."
)


def main():
    report_cfg = {"output_dir": "reports", "charts": True}
    path = reporter.write_report("knee-point", _RUNS, _PROM, _AI_PLACEHOLDER, report_cfg, HARNESS_DIR)

    # 합성 예시임을 최상단에 명확히 표기 (정직성)
    banner = ("# ⚠️ 합성 예시 리포트 (SAMPLE — 실측 아님)\n\n"
              "> 이 파일은 하네스의 출력 **형식**을 보여주기 위한 합성 데이터 결과입니다.\n"
              "> 실제 측정값이 아닙니다. `samples/generate_sample.py`로 재생성됩니다.\n\n---\n\n")
    md = path.read_text(encoding="utf-8")
    path.write_text(banner + md, encoding="utf-8")
    print("샘플 리포트 생성:", path)


if __name__ == "__main__":
    main()
