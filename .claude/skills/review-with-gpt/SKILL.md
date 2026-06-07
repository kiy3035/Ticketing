---
name: review-with-gpt
description: 현재 Claude Code와의 작업 대화를 외부 AI(OpenAI/GPT)에게 보내 "Claude가 놓친 것·잘못된 접근·더 나은 대안"을 비평받는다. 사용자가 "GPT한테 비평받아줘", "다른 AI한테 검토받아", "외부 AI 비평", "/review-with-gpt" 등을 말할 때 사용.
---

# 다른 AI에게 비평받기 (review-with-gpt)

막혔거나 한 번 더 검증하고 싶을 때, **현재 대화를 OpenAI(GPT)에게 보내 제3자 비평**을 받는다.
Claude가 스스로를 평가하면 편향되므로, 외부 모델의 시선을 빌리는 것이 목적이다.

## ⚠️ 먼저 알릴 것 (외부 전송)
- 이 스킬은 **현재 세션 대화 기록(transcript)을 OpenAI 서버로 전송**한다(코드·경로 포함).
- 전송 전 스크립트가 **비밀값(API 키/토큰/비밀번호/JWT/PEM 등)을 정규식으로 마스킹**한다.
  단 마스킹이 100%는 아니므로, 민감 코드가 많은 세션이면 `--dry-run`으로 먼저 확인할 수 있다.

## 사전 점검
1. `OPENAI_API_KEY` 환경변수가 설정돼 있는지 확인한다. 없으면 **실행하지 말고** 안내:
   - PowerShell: `$env:OPENAI_API_KEY="sk-..."` (현재 세션) — 키는 코드/파일에 적지 않는다.
2. Python이 동작하는지(`py`/`python`)는 이 레포에서 이미 확인됨.

## 절차
1. **(선택) 전송 내용 미리보기** — 사용자가 원하거나 민감 세션이면:
   ```bash
   python .claude/skills/review-with-gpt/review_with_ai.py --dry-run --out /tmp/review_payload.md
   ```
   마스킹 결과를 확인시킨 뒤 진행한다.
2. **비평 실행** (최신 세션 자동 탐지 → 마스킹 → GPT 호출):
   ```bash
   python .claude/skills/review-with-gpt/review_with_ai.py
   ```
   - 모델 바꾸려면 `--model gpt-4o-mini` 등, 또는 `OPENAI_REVIEW_MODEL` 환경변수.
   - 특정 과거 세션을 보려면 `--transcript <경로.jsonl>`.
   - 스크립트는 **현재 프로젝트의 가장 최근 .jsonl(=지금 이 세션)** 을 자동으로 고른다.
3. **결과 전달 (정직하게)**:
   - GPT의 비평을 **그대로 전달**한다. 불편한 지적이라고 누그러뜨리거나 숨기지 않는다.
   - 그다음 Claude의 입장을 덧붙인다: 타당한 지적은 **인정**하고 반영안을 제시,
     동의 못 하는 지적은 **근거를 들어** 반박한다(무조건 수용도, 무조건 방어도 금지).
   - GPT가 실제 사실관계를 틀렸으면(예: 이 레포에 없는 파일을 가정) 그 점도 짚는다.

## 정직성 원칙
- 비평을 각색하지 않는다. "GPT가 이렇게 말했다"와 "내 생각은 이렇다"를 명확히 구분한다.
- 외부 전송이 일어난다는 사실을 사용자가 인지한 상태에서만 실행한다.
- API 키는 환경변수로만. 어떤 파일·커밋·로그에도 키 값을 남기지 않는다(절대 규칙 #1).

## 참고
- 스크립트: [`review_with_ai.py`](review_with_ai.py) (표준 라이브러리만 사용, pip 설치 불필요).
- 같은 방식으로 Gemini 등 다른 모델을 붙이려면 `OPENAI_BASE_URL`(OpenAI 호환 엔드포인트) 활용 가능.
