package com.bambi.service.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Agent → Service 응답 로그 (service.ai_response_logs).
 *
 * 요청 한 건에 대한 처리 결과. 요청이 있는데 응답 Row 가 아직 없으면 = 처리 중으로 본다.
 * request_id 로 {@link AiRequestLog} 와 이어진다. response_body(jsonb)는 적재 시에만 채운다.
 */
@Entity
@Table(name = "ai_response_logs")
public class AiResponseLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "status_code")
    private Integer statusCode; // HTTP 상태코드. 2xx=성공, 그 외=실패

    // 응답 본문(jsonb). 성공 응답 또는 에러 바디를 남긴다. null 허용.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "latency_ms")
    private Integer latencyMs; // 처리 소요시간(ms)

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AiResponseLog() {
    }

    /** 적재용. request_id 로 요청 로그와 연결. */
    public AiResponseLog(Long requestId, Integer statusCode, Integer latencyMs, String responseBody) {
        this.requestId = requestId;
        this.statusCode = statusCode;
        this.latencyMs = latencyMs;
        this.responseBody = responseBody;
    }

    public Long getRequestId() {
        return requestId;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    /**
     * 응답 기록 유무·status_code 로 처리 상태를 파생한다(목록·상세 뷰 공용 규칙).
     * <ul>
     *   <li>응답 기록 없음(null) → 아직 처리 중(PROCESSING)
     *   <li>기록은 있는데 status_code null → 호출은 끝났으나 응답을 못 받음(연결 실패/타임아웃) = FAILED.
     *       ※ 여기서 PROCESSING 으로 보던 게 "영구 처리중" 버그였다.
     *   <li>status_code 있음 → 2xx SUCCESS, 그 외 FAILED
     * </ul>
     */
    public static String deriveStatus(AiResponseLog response) {
        if (response == null) {
            return "PROCESSING";
        }
        Integer code = response.getStatusCode();
        if (code == null) {
            return "FAILED";
        }
        return (code >= 200 && code < 300) ? "SUCCESS" : "FAILED";
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    /** 응답 본문(JSON 문자열 또는 연결 실패 시 에러 메시지). 관리자 상세 뷰에서만 쓴다. null 가능. */
    public String getResponseBody() {
        return responseBody;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
