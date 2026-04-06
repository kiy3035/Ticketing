# JWT 인증 (면접용 요약)

**Q. 로그아웃 후에도 JWT가 남는 문제를 어떻게 다루나?**

**A.** Access 는 짧은 TTL(30분)이고, 로그아웃 시 **jti** 를 Redis 블랙리스트에 넣어 만료 시각까지 거부한다. Refresh 는 DB `refresh_tokens` 에서 해당 **jti** 를 revoked 처리한다. Stateless JWT 만으로는 “즉시 무효”가 어렵기 때문에 **블랙리스트(또는 서버측 세션)** 가 필요하다는 점을 설명한다.

**Q. Access 만료·Refresh 유효할 때는?**

**A.** Refresh 서명·DB 유효성을 확인한 뒤 Access 만 새로 발급하고, 응답 헤더 `X-New-Access-Token` 으로 내려준다.

**Q. Refresh 만료·Access 유효할 때는?**

**A.** Access 로 사용자를 식별한 뒤 새 Refresh 를 발급·DB에 저장하고, 기존 Refresh jti 는 revoke 한다. 응답 `X-New-Refresh-Token`.

**Q. ALB 뒤 WAS 2대에서 Redis 블랙리스트가 필요한 이유는?**

**A.** Access 무효화 상태를 **모든 인스턴스가 동일하게** 참조해야 하므로 Redis 같은 공유 저장소에 둔다.

**Q. Refresh 를 DB에 두는 이유는?**

**A.** JWT만으로는 서버가 “이 Refresh 토큰을 아직 유효하다고 본다”를 강제하기 어렵다. jti 행을 두고 revoke 하면 로그아웃·탈취 대응 시 서버에서 즉시 막을 수 있다.
