package com.bambi.service.common.error;

import org.springframework.http.HttpStatus;

/**
 * 공통 에러 코드 (P0 합의안): HTTP status + 내부 code.
 * 새 에러가 필요하면 여기에 추가하고 도메인에서 재사용한다.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "입력값이 올바르지 않습니다."),
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_TOKEN", "인증 토큰이 유효하지 않습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "리소스를 찾을 수 없습니다."),
    // 온디맨드 생성에서 선택한 topic 이 내 관심사에 없을 때 (2026-08-06 계약, 여진 확정).
    // 자동 추가하지 않고 거절해 프론트가 선택 초기화·재조회·안내를 하도록 구분 가능한 코드로 내린다.
    // ⚠ 프론트 constants/errors.ts 에 이 코드 매핑이 있어야 한다 — 없으면 INTERNAL_ERROR 문구로 폴백된다.
    INTEREST_NOT_FOUND(HttpStatus.NOT_FOUND, "INTEREST_NOT_FOUND", "내 관심사에 없는 주제입니다."),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "이미 존재하는 리소스입니다."),
    // agent-api 연동 실패 매핑 (AgentGateway 가 agent 5xx/타임아웃/연결오류를 이 코드로 변환)
    AGENT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_UNAVAILABLE", "AI 처리 서비스에 일시적으로 연결할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
