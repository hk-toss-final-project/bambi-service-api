package com.bambi.service.admin.dto;

import java.util.List;

/**
 * 관리자 대시보드 개요 — 한 번의 조회로 운영 지표를 묶어 내린다.
 *
 * 모두 기존 데이터(users·reports·ai_*_logs) 집계라 새 테이블이 없다.
 * AI 지표는 요청 로그에 그 요청의 최신 응답을 붙여 파생한 상태({@link AdminAiLogResponse})를 센다.
 */
public record AdminDashboardResponse(
        Users users,
        Reports reports,
        AiCalls ai,
        List<AdminAiLogResponse> recentFailures // 최근 실패한 AI 호출(최신순 일부)
) {

    /** 사용자 수 집계. total = active + inactive. */
    public record Users(long total, long active, long inactive, long joinedToday) {
    }

    /** 리포트 수 집계(soft delete 제외). */
    public record Reports(long total, long createdToday) {
    }

    /**
     * AI 호출 집계.
     *
     * <p>성공률의 분모는 <b>끝난 호출(success + failed)</b>이다. 아직 응답을 기다리는
     * PROCESSING 을 분모에 넣으면 호출이 몰릴 때마다 성공률이 떨어져 장애처럼 보인다 —
     * 운영자가 보려는 건 "판정 난 것 중 몇 %가 성공했나"라서 이쪽이 맞다.
     *
     * @param successRate  끝난 호출 중 성공 백분율(0~100 정수). 끝난 호출이 0건이면 0.
     * @param avgLatencyMs 성공 호출의 평균 소요시간(ms). 성공 건이 없으면 null.
     */
    public record AiCalls(
            long total,
            long success,
            long failed,
            long processing,
            int successRate,
            Integer avgLatencyMs) {
    }
}
