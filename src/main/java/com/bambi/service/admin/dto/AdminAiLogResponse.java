package com.bambi.service.admin.dto;

import com.bambi.service.admin.AiRequestLog;
import com.bambi.service.admin.AiResponseLog;

import java.time.OffsetDateTime;

/**
 * 관리자 AI 처리 로그 한 줄 — 요청 + 그 결과(응답)를 합친 뷰.
 *
 * DB 에 실제로 있는 것만 담는다. 초기 admin-web 목업엔 task·model·실패사유도 있었지만
 * ai_request_logs/ai_response_logs 스키마에 없어 뺐다(endpoint 로 대체, 상태는 응답으로 파생).
 */
public record AdminAiLogResponse(
        Long id,
        OffsetDateTime requestedAt,
        String userEmail, // 요청 사용자 이메일 (user_id 가 없으면 null)
        String endpoint, // 호출한 agent 엔드포인트
        String status, // SUCCESS | FAILED | PROCESSING — 응답 유무·status_code 로 파생
        Integer latencyMs // 소요시간(ms). 아직 처리 중이면 null
) {

    public static AdminAiLogResponse of(AiRequestLog request, AiResponseLog response) {
        return new AdminAiLogResponse(
                request.getId(),
                request.getCreatedAt(),
                request.getUserEmailOrNull(),
                request.getEndpoint(),
                AiResponseLog.deriveStatus(response),
                response != null ? response.getLatencyMs() : null);
    }
}
