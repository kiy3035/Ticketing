"""
리포터 — 수집 메트릭으로 matplotlib 차트(PNG)와 마크다운 리포트를 생성한다.
Grafana 수동 스크린샷 + 수기 평균 계산 + md 작성이라는 반복 작업을 대체.

회차별 시계열을 한 차트에 겹쳐 그려 회차 간 일관성을 시각적으로 보여준다(포폴 강점).
"""
import logging
import statistics
from datetime import datetime
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # GUI 없는 서버/CI 환경에서 렌더
import matplotlib.pyplot as plt  # noqa: E402

log = logging.getLogger("harness.report")


def _plot_metric(metric_name: str, prom_per_run: list, runs: list, out_path: Path):
    """하나의 메트릭을 회차별로 겹쳐 그린다. 상대 시간(초) 기준 정렬."""
    fig, ax = plt.subplots(figsize=(9, 4))
    has_data = False
    for r, prom in zip(runs, prom_per_run):
        series = prom.get(metric_name, {}).get("series", [])
        if not series:
            continue
        t0 = series[0][0]
        xs = [ts - t0 for ts, _ in series]   # 경과 시간(초)
        ys = [v for _, v in series]
        # 축 라벨은 영문 사용 — Linux 서버/CI에 한글 폰트가 없어도 깨지지 않도록
        ax.plot(xs, ys, label=f"run {r.run_index}", linewidth=1.5)
        has_data = True
    if not has_data:
        plt.close(fig)
        return None
    ax.set_title(metric_name)
    ax.set_xlabel("elapsed (s)")
    ax.grid(True, alpha=0.3)
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=110)
    plt.close(fig)
    return out_path


def _aggregate_table(runs: list) -> str:
    """회차별 k6 핵심 지표 + 평균±표준편차 표 생성 (1·2·3회차 수기 평균 자동화)."""
    fields = [
        ("평균 RPS", "http_reqs_rate", 2),
        ("p95 지연(ms)", "http_req_duration_p95", 2),
        ("max 지연(ms)", "http_req_duration_max", 2),
        ("실패율(%)", "http_req_failed_rate", 2),
    ]
    header = "| 지표 | " + " | ".join(f"{r.run_index}회차" for r in runs) + " | 평균±표준편차 |"
    sep = "|------|" + "------|" * (len(runs) + 1)
    lines = [header, sep]
    for label, key, digits in fields:
        vals = [r.summary.get(key) for r in runs]
        nums = [v for v in vals if isinstance(v, (int, float))]
        # 실패율은 0~1 → % 변환
        scale = 100 if key == "http_req_failed_rate" else 1
        cells = [(f"{v * scale:.{digits}f}" if isinstance(v, (int, float)) else "n/a") for v in vals]
        if len(nums) >= 2:
            mean = statistics.fmean(nums) * scale
            std = statistics.pstdev(nums) * scale
            agg = f"{mean:.{digits}f} ± {std:.{digits}f}"
        elif nums:
            agg = f"{nums[0] * scale:.{digits}f}"
        else:
            agg = "n/a"
        lines.append(f"| {label} | " + " | ".join(cells) + f" | {agg} |")
    return "\n".join(lines)


def write_report(scenario: str, runs: list, prom_per_run: list,
                 ai_analysis: str, report_cfg: dict, harness_dir: Path) -> Path:
    """시나리오 1건의 전체 리포트를 디렉토리에 생성하고 마크다운 경로 반환."""
    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = harness_dir / report_cfg["output_dir"] / f"{ts}_{scenario}"
    charts_dir = out_dir / "charts"
    charts_dir.mkdir(parents=True, exist_ok=True)

    # 차트 생성 (config로 on/off)
    chart_files = []
    if report_cfg.get("charts", True):
        metric_names = list(prom_per_run[0].keys()) if prom_per_run else []
        for name in metric_names:
            p = _plot_metric(name, prom_per_run, runs, charts_dir / f"{name}.png")
            if p:
                chart_files.append(p)
        log.info("[%s] 차트 %d개 생성", scenario, len(chart_files))

    # 마크다운 작성
    md = []
    md.append(f"# 부하 테스트 리포트 — {scenario}")
    md.append(f"\n생성: {datetime.now().isoformat(timespec='seconds')}  |  회차: {len(runs)}회")
    md.append("\n> ⚠️ 아래 'AI 보조 분석'은 참고용 진단이며, 판단 근거는 원본 메트릭 표/차트입니다.\n")

    md.append("## 회차별 요약 (k6 클라이언트 측정)")
    md.append(_aggregate_table(runs) + "\n")

    if chart_files:
        md.append("## 서버측 메트릭 차트 (회차 겹쳐보기)")
        for p in chart_files:
            rel = p.relative_to(out_dir).as_posix()
            md.append(f"\n### {p.stem}\n\n![{p.stem}]({rel})")
        md.append("")

    md.append("## AI 보조 분석 (Gemini)")
    md.append(ai_analysis + "\n")

    md.append("## 부록 — 원본 데이터")
    md.append("- k6 원본 summary JSON: `reports/_raw/` 참조")
    for r in runs:
        md.append(f"  - {r.run_index}회차: `{Path(r.raw_summary_path).name}` (rc={r.return_code})")

    report_path = out_dir / "report.md"
    report_path.write_text("\n".join(md), encoding="utf-8")
    log.info("[%s] 리포트 생성 완료 → %s", scenario, report_path)
    return report_path
