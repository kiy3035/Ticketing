"""
advisor.py 단위 테스트 — AI 응답을 신뢰하지 않는 방어선(parse_proposals/sanitize)을 검증.

여기 테스트는 전부 결정론적이고 앱·API·네트워크가 필요 없다. 즉 "AI가 무슨 말을 하든
코드가 화이트리스트·범위로 막는다"는 가드레일을 증명한다.
"""
import advisor

# 화이트리스트(축약) — 실제 config.yaml 구조와 동일
PARAMS = {
    "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE": {"current": 30, "min": 10, "max": 80},
    "SPRING_DATA_REDIS_LETTUCE_POOL_MAX_ACTIVE": {"current": 20, "min": 8, "max": 64},
}


# ── parse_proposals: 잡텍스트/코드펜스/오류에 견고 ────────────────────────
def test_parse_plain_json():
    text = '{"proposals": [{"env": "X", "proposed": 40}]}'
    assert advisor.parse_proposals(text) == [{"env": "X", "proposed": 40}]


def test_parse_with_code_fence():
    text = "```json\n{\"proposals\": [{\"env\": \"X\", \"proposed\": 40}]}\n```"
    assert advisor.parse_proposals(text)[0]["proposed"] == 40


def test_parse_with_surrounding_prose():
    text = "분석 결과입니다:\n{\"proposals\": []}\n참고하세요."
    assert advisor.parse_proposals(text) == []


def test_parse_malformed_returns_empty():
    assert advisor.parse_proposals("이건 JSON이 아님") == []
    assert advisor.parse_proposals("") == []
    assert advisor.parse_proposals('{"proposals": "배열아님"}') == []


# ── sanitize: 화이트리스트 거부 ──────────────────────────────────────────
def test_reject_non_whitelisted_param():
    props = [{"env": "RANDOM_DANGEROUS_FLAG", "proposed": 1}]
    accepted, rejected = advisor.sanitize(props, PARAMS)
    assert accepted == []
    assert len(rejected) == 1 and "화이트리스트" in rejected[0]["reason"]


# ── sanitize: 범위 밖 clamp ──────────────────────────────────────────────
def test_clamp_above_max():
    props = [{"env": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "proposed": 9999}]
    accepted, rejected = advisor.sanitize(props, PARAMS)
    assert len(accepted) == 1
    assert accepted[0]["proposed"] == 80          # max로 clamp
    assert accepted[0]["clamped_from"] == 9999     # 원래 값 표시


def test_clamp_below_min():
    props = [{"env": "SPRING_DATA_REDIS_LETTUCE_POOL_MAX_ACTIVE", "proposed": 1}]
    accepted, _ = advisor.sanitize(props, PARAMS)
    assert accepted[0]["proposed"] == 8            # min으로 clamp


# ── sanitize: 변화 없음 / 형식 오류 거부 ─────────────────────────────────
def test_reject_no_op_after_clamp():
    # current=30. clamp 후에도 30이면 '변화 없음'으로 거부
    props = [{"env": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "proposed": 30}]
    accepted, rejected = advisor.sanitize(props, PARAMS)
    assert accepted == [] and "변화 없음" in rejected[0]["reason"]


def test_reject_non_integer():
    props = [{"env": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "proposed": "많이"}]
    accepted, rejected = advisor.sanitize(props, PARAMS)
    assert accepted == [] and "정수" in rejected[0]["reason"]


def test_valid_proposal_passes():
    props = [{"env": "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", "proposed": 50,
              "rationale": "pending 높음", "confidence": "medium"}]
    accepted, rejected = advisor.sanitize(props, PARAMS)
    assert rejected == []
    assert accepted[0]["proposed"] == 50 and accepted[0]["clamped_from"] is None


# ── render_md: 키 없을 때 graceful ───────────────────────────────────────
def test_render_ai_skipped_banner():
    md = advisor.render_md("knee-point", [], [], ai_skipped=True)
    assert "GEMINI_API_KEY 미설정" in md
