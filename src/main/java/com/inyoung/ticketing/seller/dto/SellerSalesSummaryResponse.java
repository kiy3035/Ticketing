package com.inyoung.ticketing.seller.dto;

import java.util.List;

/**
 * 판매자 공연별 매출 요약 + 결제 목록
 */
public class SellerSalesSummaryResponse {
	private Long totalRevenue;
	private int totalCount;
	private List<SellerPaymentResponse> payments;

	public SellerSalesSummaryResponse(Long totalRevenue, int totalCount, List<SellerPaymentResponse> payments) {
		this.totalRevenue = totalRevenue != null ? totalRevenue : 0L;
		this.totalCount = totalCount;
		this.payments = payments != null ? payments : List.of();
	}

	public Long getTotalRevenue() { return totalRevenue; }
	public int getTotalCount() { return totalCount; }
	public List<SellerPaymentResponse> getPayments() { return payments; }
}
