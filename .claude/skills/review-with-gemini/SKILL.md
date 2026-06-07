---
name: review-with-gemini
description: 현재 Claude Code와의 작업 대화 또는 특정 PR을 외부 AI(Google Gemini)에게 보내 "놓친 것·잘못된 접근·더 나은 대안"을 비평받는다. 사용자가 "Gemini한테 비평받아줘", "다른 AI한테 검토받아", "외부 AI 비평", "/review-with-gemini", "/review-with-gemini 9"(PR 번호) 등을 말할 때 사용.
---

# 다른 AI에게 비평받기 (review-with-gemini)

막혔거나 한 번 더 검증하고 싶을 때, **현재 대화 또는 특정 PR을 Google Gemini에게 보내 제3자 비평**을 받는다.
Claude가 스스로를 평가하면 편향되므로, 외부 모델의 시선을 빌리는 것이 목적이다.

**두 가지 모드:**
- **대화 모드**(기본): 현재 세션 대화를 비평 — 대화 중 이상함을 느꼈을 때.
- **PR 모드**(`/review-with-gemini <PR번호>`): 해당 PR의 본문+diff를 비평 — PR을 읽다 이상할 때.

## ⚠️ 먼저 알릴 것 (외부 전송 + 무료 티어)
- 이 스킬은 **현재 세션 대화/PR 내용을 Google Gemini 서버로 전송**한다(코드·경로 포함).
- 전송 전 스크립트가 **비밀값(API 키/토큰/비밀번호/JWT/PEM/Google키 등)을 정규식으로 마스킹**한다.
  단 마스킹이 100%는 아니므로, 민감 코드가 많은 세션이면 `--dry-run`으로 먼저 확인할 수 있다.
- **무료 티어 주의**: Gemini 무료 티어는 Google이 입력 데이터를 제품 개선에 사용할 수 있다.
  민감한 내용이면 `--dry-run`으로 전송 범위를 먼저 점검할 것.

## 사전 점검
1. `GEMINI_API_KEY` 환경변수가 설정돼 있는지 확인한다. 없으면 **실행하지 말고** 안내:
   - 키 발급(무료): https://aistudio.google.com/apikey
   - PowerShell(영구): `[Environment]::SetEnvironmentVariable("GEMINI_API_KEY","AIza...","User")` 후 Claude Code 재시작
   - 키는 코드/파일/채팅에 적지 않는다(절대 규칙 #1).
2. Python이 동작하는지(`py`/`python`)는 이 레포에서 이미 확인됨.

## 절차
1. **(선택) 전송 내용 미리보기** — 사용자가 원하거나 민감 세션이면:
   ```bash
   python .claude/skills/review-with-gemini/review_with_gemini.py --dry-run --out /tmp/review_payload.md
   ```
   마스킹 결과를 확인시킨 뒤 진행한다.
2. **비평 실행**:
   - **대화 모드** (최신 세션 자동 탐지 → 마스킹 → Gemini 호출):
     ```bash
     python .claude/skills/review-with-gemini/review_with_gemini.py
     ```
   - **PR 모드** (사용자가 PR 번호를 줬을 때 — 예: "PR 9 비평받아줘", "/review-with-gemini 9"):
     ```bash
     python .claude/skills/review-with-gemini/review_with_gemini.py --pr 9
     ```
     `gh pr view`/`gh pr diff`로 PR 본문+diff를 가져온다. **`gh`(GitHub CLI)가 PATH에 있어야** 하며,
     없으면 `GH_BIN` 환경변수로 경로 지정(예: `GH_BIN="/c/Program Files/GitHub CLI/gh.exe"`).
   - 모델 바꾸려면 `--model gemini-2.5-flash`(품질↑) 등, 또는 `GEMINI_MODEL` 환경변수(기본 `gemini-2.5-flash-lite` — 무료 할당량 최대).
     ⚠️ `gemini-2.0-flash`·`gemini-1.5-flash`는 2026-03-03 retire되어 사용 불가(429 limit:0 / 404 발생).
   - 특정 과거 세션을 보려면 `--transcript <경로.jsonl>`.
   - 대화 모드는 **현재 프로젝트의 가장 최근 .jsonl(=지금 이 세션)** 을 자동으로 고른다.
   - **기록 남기기**: `--save` 를 붙이면 비평을 `docs/ai-reviews/PR{N}-{날짜}.md`(또는 `session-{날짜}.md`)로
     자동 저장한다. 평소엔 화면 출력만, 남길 가치가 있을 때만 `--save`. (특정 경로는 `--out <파일>`)
3. **결과 전달 (정직하게)**:
   - Gemini의 비평을 **그대로 전달**한다. 불편한 지적이라고 누그러뜨리거나 숨기지 않는다.
   - 그다음 Claude의 입장을 덧붙인다: 타당한 지적은 **인정**하고 반영안을 제시,
     동의 못 하는 지적은 **근거를 들어** 반박한다(무조건 수용도, 무조건 방어도 금지).
   - Gemini가 실제 사실관계를 틀렸으면(예: 이 레포에 없는 파일을 가정) 그 점도 짚는다.

## 정직성 원칙
- 비평을 각색하지 않는다. "Gemini가 이렇게 말했다"와 "내 생각은 이렇다"를 명확히 구분한다.
- 외부 전송이 일어난다는 사실을 사용자가 인지한 상태에서만 실행한다.
- API 키는 환경변수로만. 어떤 파일·커밋·로그에도 키 값을 남기지 않는다(절대 규칙 #1).

## 참고
- 스크립트: [`review_with_gemini.py`](review_with_gemini.py) (표준 라이브러리만 사용, pip 설치 불필요).
- Gemini의 **OpenAI 호환 엔드포인트**(`generativelanguage.googleapis.com/v1beta/openai`)를 사용한다.
  다른 OpenAI 호환 모델로 바꾸려면 `GEMINI_BASE_URL`·`GEMINI_MODEL`만 교체하면 된다.
