package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentClippingRequest;
import com.bambi.service.agent.dto.AgentAcceptedJob;
import com.bambi.service.agent.dto.AgentContentMarkDeletionRequest;
import com.bambi.service.agent.dto.AgentContentMarkRequest;
import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.agent.dto.AgentInterestTaxonomyRequest;
import com.bambi.service.agent.dto.AgentUrlSourceRequest;

/**
 * Service → agent-api(FastAPI) 실제 호출 경계.
 * agent 계약(내부 비동기)을 Spring 쪽에서 감싸고, 호출/응답을 AI 로그로 남기며,
 * agent 오류를 팀 공통 에러({@link com.bambi.service.common.error.ErrorCode})로 변환한다.
 *
 * <p>P0 의 {@link AgentClient}(북마크/카드 Mock, 동기)와 별개다. 이 Gateway 는
 * 컨텍스트 동기화처럼 "동기/비동기 결정과 무관한 공통 연동"부터 붙인다.
 */
public interface AgentGateway {

    /**
     * 사용자 컨텍스트를 agent 에 upsert 한다. (계약: PUT /internal/v1/users/{userId}/context)
     * 가입 직후 1회 필수 — 없으면 이후 생성 요청이 agent 에서 거부된다.
     *
     * @throws com.bambi.service.common.error.ApiException agent 연결 실패/5xx 시 AGENT_UNAVAILABLE
     */
    void syncUserContext(long userId, AgentContextRequest request);

    /** Service가 소유한 활성 관심사 taxonomy를 Agent DB에 버전 Snapshot으로 동기화한다. */
    void syncInterestTaxonomy(AgentInterestTaxonomyRequest request);

    /**
     * 웹 클리핑을 agent 의 개인 Wiki 처리 Job 으로 등록한다.
     * (계약: POST /internal/v1/users/{userId}/wiki-sources/clippings → 202 Accepted)
     *
     * <p>비동기 접수만 확인한다(카드/위키 결과는 나중에 service-worker 가 Pull). 저장 플로우에서
     * 부가로 호출되므로, 호출부는 이 실패를 삼켜 저장 자체는 막지 않는다(컨텍스트 동기화와 동일 정책).
     *
     * @throws com.bambi.service.common.error.ApiException agent 연결 실패/5xx 시 AGENT_UNAVAILABLE
     */
    AgentAcceptedJob relayClipping(long userId, AgentClippingRequest request);

    /**
     * 본문 없는 URL 저장을 agent 의 개인 Wiki 처리 Job 으로 등록한다.
     * (계약: POST /internal/v1/users/{userId}/wiki-sources/urls → 202 Accepted)
     *
     * <p>{@link #relayClipping} 과 동일 정책 — 비동기 접수만 확인하고, 호출부는 실패를 삼킨다.
     *
     * @throws com.bambi.service.common.error.ApiException agent 연결 실패/5xx 시 AGENT_UNAVAILABLE
     */
    AgentAcceptedJob relayUrlSource(long userId, AgentUrlSourceRequest request);

    /** 북마크한 카드 콘텐츠를 Agent 개인 Wiki 원본으로 편입한다. */
    AgentAcceptedJob relayContentMark(long userId, AgentContentMarkRequest request);

    /** 북마크 원본 연결을 해제하고 남은 활성 원본 기준 Wiki 재구성을 요청한다. */
    AgentAcceptedJob relayContentMarkDeletion(long userId, AgentContentMarkDeletionRequest request);
}
