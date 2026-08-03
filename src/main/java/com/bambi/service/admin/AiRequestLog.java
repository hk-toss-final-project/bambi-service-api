package com.bambi.service.admin;

import com.bambi.service.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * Service → Agent 요청 로그 (service.ai_request_logs).
 *
 * 관리자 화면 조회 + AgentGateway 적재(P1) 양쪽에서 쓴다.
 * 조회는 endpoint·user·created_at 만 쓰고, request_body(jsonb)는 적재 시에만 채운다.
 */
@Entity
@Table(name = "ai_request_logs")
public class AiRequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 어떤 사용자의 요청이었는지. 이메일 표시를 위해 연결해 둔다(목록이라 LAZY).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String endpoint; // 호출한 agent 엔드포인트 (예: /internal/v1/users/{id}/context)

    // 요청 본문(jsonb). 조회 화면엔 안 쓰지만 적재 시 남긴다. null 허용.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body")
    private String requestBody;

    // DB default(now())가 채운다. 읽기 전용이라 삽입/수정 대상에서 뺀다.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected AiRequestLog() {
    }

    /** 적재용. user 는 null 가능(호출 주체가 사용자와 무관한 경우). request_body 는 JSON 문자열. */
    public AiRequestLog(User user, String endpoint, String requestBody) {
        this.user = user;
        this.endpoint = endpoint;
        this.requestBody = requestBody;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    /** 요청 사용자 이메일. 호출 주체가 사용자와 무관하면(user null) null. */
    public String getUserEmailOrNull() {
        return user != null ? user.getEmail() : null;
    }

    public String getEndpoint() {
        return endpoint;
    }

    /** 요청 본문(JSON 문자열). 관리자 상세 뷰에서만 쓴다. 적재 전이면 null. */
    public String getRequestBody() {
        return requestBody;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
