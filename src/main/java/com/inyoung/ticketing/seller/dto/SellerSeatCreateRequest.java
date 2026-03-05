package com.inyoung.ticketing.seller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 구역별 좌석 일괄 생성 요청 (예: A구역 1~10번, 가격 50000)
 */
public class SellerSeatCreateRequest {
	@NotBlank(message = "구역을 입력하세요")
	private String section;
	@NotNull(message = "시작 좌석 번호를 입력하세요")
	@Min(1)
	private Integer seatNoFrom;
	@NotNull(message = "끝 좌석 번호를 입력하세요")
	@Min(1)
	private Integer seatNoTo;
	@NotNull(message = "가격을 입력하세요")
	@Min(0)
	private Long price;

	public String getSection() { return section; }
	public void setSection(String section) { this.section = section; }
	public Integer getSeatNoFrom() { return seatNoFrom; }
	public void setSeatNoFrom(Integer seatNoFrom) { this.seatNoFrom = seatNoFrom; }
	public Integer getSeatNoTo() { return seatNoTo; }
	public void setSeatNoTo(Integer seatNoTo) { this.seatNoTo = seatNoTo; }
	public Long getPrice() { return price; }
	public void setPrice(Long price) { this.price = price; }
}
