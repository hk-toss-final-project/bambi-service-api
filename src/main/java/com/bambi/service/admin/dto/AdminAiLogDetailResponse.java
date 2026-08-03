package com.bambi.service.admin.dto;

import com.bambi.service.admin.AiRequestLog;
import com.bambi.service.admin.AiResponseLog;

import java.time.OffsetDateTime;

/**
 * 관리자 AI 로그 <b>상세</b> — 목록 한 줄({@link AdminAiLogResponse})에 요청·응답 본문을 더한 뷰.
 *
 * <p>목록은 본문을 빼고 가볍게 내려주고, 관리자가 한 건을 눌렀을 때만 이 상세로 본문을 본다.
 * {@code requestBody}/{@code responseBody}는 저장된 원문 문자열 그대로 내보낸다(대개 JSON,
 * agent 연결 실패 시 응답 본문은 에러 메시지 평문일 수 있음) — 화면에서 보기 좋게 정리한다.
 */
public record AdminAiLogDetailResponse(
        Long id,
        OffsetDateTime requestedAt,
        String userEmail,
        String endpoint,
        String status, // SUCCESS | FAILED | PROCESSING — 목록과 같은 규칙으로 파생
        Integer statusCode, // agent 응답 HTTP 코드. 응답 없음/연결 실패면 null
        Integer latencyMs, // 소요시간(ms). 처리 중이면 null
        OffsetDateTime respondedAt, // 응답 기록 시각. 처리 중이면 null
        String requestBody, // service → agent 요청 본문 원문(JSON 문자열). 적재 전이면 null
        String responseBody // agent → service 응답 본문 원문. 처리 중/실패면 null 일 수 있음
) {

    public static AdminAiLogDetailResponse of(AiRequestLog request, AiResponseLog response) {
        return new AdminAiLogDetailResponse(
                request.getId(),
                request.getCreatedAt(),
                request.getUserEmailOrNull(),
                request.getEndpoint(),
                AiResponseLog.deriveStatus(response),
                response != null ? response.getStatusCode() : null,
                response != null ? response.getLatencyMs() : null,
                response != null ? response.getCreatedAt() : null,
                request.getRequestBody(),
                response != null ? response.getResponseBody() : null);
    }
}
