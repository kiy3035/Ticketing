package com.inyoung.ticketing.hold.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 홀드 생성 요청 DTO.
 * <p>
 * {@code record} 는 Java 16+ 불변 데이터 캐리어: 컴파일러가 생성자·접근자({@code concertId()}, {@code seatId()})·
 * {@code equals}/{@code hashCode}/{@code toString} 를 만들어 준다. Jackson은 JSON 바인딩 시 필드명을 컴포넌트 이름과 맞춘다.
 * </p>
 * <p>
 * {@code @Valid} + {@code @NotNull} 은 record 컴포넌트에도 적용 가능하다(Bean Validation 3.x).
 * </p>
 */
public record HoldRequest(
	@NotNull Long concertId,
	@NotNull Long seatId
) {
}
