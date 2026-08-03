package com.bambi.service.agent;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * agent HTTP 호출 실패를 팀 공통 에러({@link ErrorCode#AGENT_UNAVAILABLE})로 변환하는 공용 매퍼.
 *
 * <p>여러 agent 클라이언트(컨텍스트 게이트웨이·위키 조회·발행 claim/ack)가 같은 규칙을 쓰므로
 * 에러 코드 선택과 사유 문구를 한 곳에 모은다 — 상태코드 표기·연결 실패 문구가 흩어지지 않게.
 */
public final class AgentErrors {

    private AgentErrors() {
    }

    /** agent 가 응답은 줬으나 4xx/5xx — 작업 사유에 상태코드를 붙인다. */
    public static ApiException unavailable(RestClientResponseException e, String opMessage) {
        return new ApiException(ErrorCode.AGENT_UNAVAILABLE,
                opMessage + " (status=" + e.getStatusCode().value() + ")");
    }

    /** 연결 실패·타임아웃 등 응답 자체가 없는 경우. */
    public static ApiException connectFailed(RestClientException e) {
        return new ApiException(ErrorCode.AGENT_UNAVAILABLE, "agent 연결 실패: " + e.getMessage());
    }
}
