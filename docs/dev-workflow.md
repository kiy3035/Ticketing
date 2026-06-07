# 개발 워크플로우

이 프로젝트의 표준 작업 흐름. **Plan 먼저, 작게 반복**이 핵심이다.

## 1. 큰 변경은 Plan Mode 먼저

새 기능·구조 변경 등 큰 작업은 바로 코드부터 짜지 않는다.

1. **Plan Mode**(Claude Code: `Shift+Tab`)로 작업을 설명한다.
2. Claude가 **계획**(수정할 파일·접근·트레이드오프)을 제시한다.
3. 계획을 **리뷰·피드백**한다.
4. 만족하면 전환해 **실행**한다.

> 이유: 계획 없이 바로 실행하면 방향이 틀어진 채 대량 수정이 일어나 되돌리기 어렵다.
> (CLAUDE.md 절대 규칙 "설계 먼저"와 같은 취지)

## 2. 작은 루프 (TDD 지향)

> **변경 → 테스트 → 린트 → 커밋 → 반복**

1. 기능/수정 **하나**만 작게 한다.
2. **테스트**로 확인한다.
3. **린트/포맷**을 본다.
4. 통과하면 **커밋**한다.
5. 다음으로 넘어간다.

작게 커밋하면 문제가 생겨도 **마지막 커밋으로 되돌리면** 되니 디버깅이 쉽다.

## 3. 명령어 (영역별)

### Python — 부하 테스트 하네스 (`loadtest-harness/`)
```bash
cd loadtest-harness
pip install -r requirements-dev.txt   # 최초 1회 (pytest, ruff 포함)

ruff check .       # 린트 (CI와 동일)
ruff check . --fix # 자동수정 가능한 것 정리
ruff format .      # 포맷
pytest -q          # 단위 테스트

# 한 번에 (커밋 전 권장)
ruff check . && pytest -q
```
설정: [`loadtest-harness/ruff.toml`](../loadtest-harness/ruff.toml)

### Java — 앱
```bash
./gradlew test         # 테스트만
./gradlew clean build  # 빌드(+테스트)
```
> Java 린터/포매터(spotless 등)는 아직 미도입. 추후 도입 시 이 문서에 추가한다.

## 4. 강제 수준

- **수동**: 커밋 전에 위 "한 번에" 명령을 돌린다(개발자 책임).
- **CI 자동 검증**: `loadtest-harness/**` 변경 시 [`Harness CI`](../.github/workflows/loadtest-harness-ci.yml)가
  **ruff check → pytest**를 자동 실행한다. PR이 빨간불이면 머지 전에 잡는다.
- pre-commit 훅으로 커밋 자체를 막는 방식은 (마찰 때문에) 채택하지 않았다. 안전망은 CI가 담당.

## 5. 커밋/PR

작업 단위가 끝나면 `main`에서 분기한 브랜치 → PR. 상세는 [`.claude/skills/commit`](../.claude/skills/commit/SKILL.md).
