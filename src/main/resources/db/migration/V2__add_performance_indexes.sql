-- ============================================================
-- V2: 성능 인덱스 추가
-- 쿼리 패턴 분석 기반으로 자주 조회되는 컬럼 조합에 복합 인덱스를 설정한다.
-- ============================================================

-- 예약 조회: 사용자별 + 상태별 (예매 내역 화면)
CREATE INDEX idx_reservation_user_status ON reservation (user_id, status);

-- 콘서트 조회: 상태 + 공연일시 (예매 가능 목록, 지난 공연 필터)
CREATE INDEX idx_concert_status_at ON concert (status, concert_at);

-- 결제 조회: 상태 + 완료일시 (관리자 통계, 환불 배치)
CREATE INDEX idx_payment_status_completed ON payment (status, completed_at);

-- 결제 조회: 사용자별 (결제 내역)
CREATE INDEX idx_payment_user_id ON payment (user_id);

-- 결제 조회: 콘서트별 + 상태 (판매자 매출, 환불 배치)
CREATE INDEX idx_payment_concert_status ON payment (concert_id, status);

-- 좌석 조회: 콘서트별 + 상태 (가용 좌석 수 계산)
CREATE INDEX idx_seat_concert_status ON seat (concert_id, status);
