"""도메인 가드레일 — 결정적 검사 로직 테스트 (네트워크/AI 불필요)."""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import domain_guard as dg  # noqa: E402

_DIFF = """diff --git a/src/main/java/com/inyoung/ticketing/lock/LockService.java b/src/main/java/com/inyoung/ticketing/lock/LockService.java
--- a/src/main/java/com/inyoung/ticketing/lock/LockService.java
+++ b/src/main/java/com/inyoung/ticketing/lock/LockService.java
@@ -10,4 +10,8 @@ public class LockService {
     public void doWork() {
-        legacy();
+        Thread.sleep(5000);
+        String apiKey = "sk-abcdefghijklmnopqrstuvwxyz123456";
+        String fromEnv = System.getenv("GEMINI_API_KEY");
+        String pw = "${DB_PASSWORD}";
+        // 정상 주석 라인
     }
"""


def test_추가라인만_추출하고_파일과_라인번호를_단다():
    added = dg.parse_added_lines(_DIFF)
    texts = [t for _, _, t in added]
    # 추가(+) 라인만, 삭제(-)/헤더 제외
    assert any("Thread.sleep(5000)" in t for t in texts)
    assert all("legacy()" not in t for t in texts)  # 삭제 라인 제외
    f, ln, _ = added[0]
    assert f.endswith("lock/LockService.java")
    assert ln == 11  # @@ +10 의 컨텍스트(public void) 다음 추가라인


def test_비밀값은_CRITICAL로_잡고_env참조는_제외():
    findings = dg.check_secrets(dg.parse_added_lines(_DIFF))
    rules = [f["rule"] for f in findings]
    assert "비밀값 커밋 금지" in rules
    crit = [f for f in findings if f["severity"] == "CRITICAL"]
    assert len(crit) == 1  # sk-... 1건만
    # System.getenv 라인과 ${DB_PASSWORD} placeholder는 오탐 아님
    assert all("getenv" not in f["snippet"] for f in findings)
    assert all("${" not in f["snippet"] for f in findings)


def test_하드코딩_타임아웃은_WARNING():
    findings = dg.check_hardcode(dg.parse_added_lines(_DIFF))
    assert any(f["rule"] == "임계치/설정값 하드코딩 금지" for f in findings)
    assert all(f["severity"] == "WARNING" for f in findings)


def test_위반없는_diff는_빈결과():
    clean = (
        "+++ b/src/main/java/com/inyoung/ticketing/seat/Seat.java\n"
        "@@ -1,1 +1,2 @@\n"
        "+    // 좌석 도메인 설명 주석 추가\n"
    )
    added = dg.parse_added_lines(clean)
    assert dg.check_secrets(added) == []
    assert dg.check_hardcode(added) == []
