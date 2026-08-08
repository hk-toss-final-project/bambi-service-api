package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentClippingRequest;
import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.agent.dto.AgentInterestTaxonomyRequest;
import com.bambi.service.agent.dto.AgentUrlSourceRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * {@link AgentGateway} 의 실제 구현 — RestClient 로 agent-api 를 HTTP 호출한다.
 * 호출 전후를 {@link AgentCallLogger} 로 남기고, agent 오류를 팀 공통 에러로 변환한다.
 */
@Component
public class RestClientAgentGateway implements AgentGateway {

    private static final Logger log = LoggerFactory.getLogger(RestClientAgentGateway.class);

    private final RestClient restClient;
    private final String internalPrefix;
    private final AgentCallLogger callLogger;
    private final ObjectMapper objectMapper;

    public RestClientAgentGateway(
            RestClient agentRestClient,
            @Value("${app.agent.internal-prefix}") String internalPrefix,
            AgentCallLogger callLogger,
            ObjectMapper objectMapper) {
        this.restClient = agentRestClient;
        this.internalPrefix = internalPrefix;
        this.callLogger = callLogger;
        this.objectMapper = objectMapper;
    }

    @Override
    public void syncUserContext(long userId, AgentContextRequest request) {
        String path = internalPrefix + "/users/" + userId + "/context";
        String requestBody = toJson(request);
        Long reqLogId = safeLogRequest(userId, path, requestBody);
        long startNanos = System.nanoTime();

        try {
            ResponseEntity<String> resp = restClient.put()
                    .uri(path)
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody != null ? requestBody : request)   // 로그용 JSON 재사용(이중 직렬화 회피)
                    .retrieve()
                    .toEntity(String.class);
            safeLogResponse(reqLogId, resp.getStatusCode().value(), elapsedMs(startNanos), resp.getBody());

        } catch (RestClientResponseException e) {
            // agent 가 응답은 줬으나 4xx/5xx
            int status = e.getStatusCode().value();
            String body = e.getResponseBodyAsString();
            safeLogResponse(reqLogId, status, elapsedMs(startNanos), body);

            // STALE_CONTEXT_VERSION: service 로컬 카운터가 agent 실제 버전보다 낮아 거절됨.
            // agent 가 현재 버전(current_context_version)을 실어주면 그 값으로 재전송하도록 신호를 던진다.
            // (구 agent 는 이 필드가 없으므로 예전처럼 "이미 최신"으로 간주해 통과 — 하위호환)
            if (status == 409 && body != null && body.contains("STALE_CONTEXT_VERSION")) {
                Integer current = parseCurrentContextVersion(body);
                if (current != null) {
                    log.info("[AgentGateway] context STALE(userId={}) — agent current={} 로 재전송 신호", userId, current);
                    throw new StaleContextVersionException(current);
                }
                log.info("[AgentGateway] context STALE(userId={}) — current_context_version 없음(구 agent), 무시", userId);
                return;
            }
            throw AgentErrors.unavailable(e, "agent 컨텍스트 동기화 실패");

        } catch (RestClientException e) {
            // 연결 실패/타임아웃 등 (응답 자체가 없음)
            safeLogResponse(reqLogId, null, elapsedMs(startNanos), e.getMessage());
            throw AgentErrors.connectFailed(e);
        }
    }

    @Override
    public void syncInterestTaxonomy(AgentInterestTaxonomyRequest request) {
        String path = internalPrefix + "/interest-taxonomies/" + request.version();
        String requestBody = toJson(request);
        Long reqLogId = safeLogRequest(null, path, requestBody);
        long startNanos = System.nanoTime();
        try {
            ResponseEntity<String> resp = restClient.put()
                    .uri(path)
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody != null ? requestBody : request)
                    .retrieve()
                    .toEntity(String.class);
            safeLogResponse(
                    reqLogId, resp.getStatusCode().value(), elapsedMs(startNanos), resp.getBody());
        } catch (RestClientResponseException e) {
            safeLogResponse(
                    reqLogId,
                    e.getStatusCode().value(),
                    elapsedMs(startNanos),
                    e.getResponseBodyAsString());
            throw AgentErrors.unavailable(e, "agent 관심사 taxonomy 동기화 실패");
        } catch (RestClientException e) {
            safeLogResponse(reqLogId, null, elapsedMs(startNanos), e.getMessage());
            throw AgentErrors.connectFailed(e);
        }
    }

    @Override
    public void relayClipping(long userId, AgentClippingRequest request) {
        postWikiSource(userId, "/wiki-sources/clippings", request, "agent 클리핑 중계 실패");
    }

    @Override
    public void relayUrlSource(long userId, AgentUrlSourceRequest request) {
        postWikiSource(userId, "/wiki-sources/urls", request, "agent URL 중계 실패");
    }

    /**
     * 위키 원천 처리 POST 공통 로직(clippings·urls). 202 접수만 확인하고, 응답을 AI 로그로 남기며
     * agent 오류를 AGENT_UNAVAILABLE 로 변환한다. clipping/url 은 경로와 실패 문구만 다르다.
     */
    private void postWikiSource(long userId, String pathSuffix, Object request, String failMessage) {
        String path = internalPrefix + "/users/" + userId + pathSuffix;
        String requestBody = toJson(request);
        Long reqLogId = safeLogRequest(userId, path, requestBody);
        long startNanos = System.nanoTime();

        try {
            ResponseEntity<String> resp = restClient.post()
                    .uri(path)
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody != null ? requestBody : request)   // 로그용 JSON 재사용(이중 직렬화 회피)
                    .retrieve()
                    .toEntity(String.class);
            // 202 Accepted 가 정상 — Job 접수만 확인한다(결과는 service-worker Pull).
            safeLogResponse(reqLogId, resp.getStatusCode().value(), elapsedMs(startNanos), resp.getBody());

        } catch (RestClientResponseException e) {
            safeLogResponse(reqLogId, e.getStatusCode().value(), elapsedMs(startNanos), e.getResponseBodyAsString());
            throw AgentErrors.unavailable(e, failMessage);

        } catch (RestClientException e) {
            safeLogResponse(reqLogId, null, elapsedMs(startNanos), e.getMessage());
            throw AgentErrors.connectFailed(e);
        }
    }

    /**
     * 409 STALE 응답 body 에서 agent 현재 버전(error.details.current_context_version)을 읽는다.
     * 필드가 없거나(구 agent) 파싱 실패면 null → 호출부가 예전처럼 무시하도록 한다(하위호환).
     */
    private Integer parseCurrentContextVersion(String body) {
        try {
            var node = objectMapper.readTree(body)
                    .path("error").path("details").path("current_context_version");
            return node.isIntegralNumber() ? node.asInt() : null;
        } catch (Exception e) {
            log.warn("[AgentGateway] STALE body 파싱 실패 — current_context_version 무시", e);
            return null;
        }
    }

    /** 로그 적재용 요청 본문 직렬화. 실패해도 호출은 계속하고 본문만 비운다. */
    /**
     * 로그 적재는 <b>호출 결과를 절대 바꾸지 않는다.</b>
     *
     * <p>로그 저장이 실패하면 그 예외가 원래 던지려던 agent 오류를 덮어써서,
     * {@code AGENT_UNAVAILABLE} 이 나가야 할 자리에 {@code INTERNAL_ERROR} 500 이 나간다
     * (2026-08-08 여진 발견 — agent 장애를 service 버그로 오진하게 된다).
     * 원인인 본문 형식은 {@code AgentCallLogger} 에서 막았지만, <b>관측용 코드가 비즈니스 결과를
     * 바꿀 수 있는 구조 자체</b>를 여기서 끊는다. 로그가 못 남는 건 감수해도 호출 결과가
     * 뒤바뀌면 안 된다.
     *
     * <p>⚠️ {@code REQUIRES_NEW} 라 실제 INSERT 는 프록시가 커밋할 때 일어난다 — 그 예외는
     * <b>프록시 호출을 감싼 여기서만</b> 잡힌다. {@code logResponse} 안에서 try/catch 해도 못 잡는다.
     */
    private void safeLogResponse(Long requestId, Integer statusCode, Integer latencyMs, String responseBody) {
        try {
            callLogger.logResponse(requestId, statusCode, latencyMs, responseBody);
        } catch (RuntimeException e) {
            log.warn("[AgentGateway] 응답 로그 적재 실패 — 호출 결과에는 영향 없음(requestId={})", requestId, e);
        }
    }

    /** 요청 로그 적재 실패도 호출을 막지 않는다. id 가 null 이면 응답 로그는 알아서 건너뛴다. */
    private Long safeLogRequest(Long userId, String endpoint, String requestBody) {
        try {
            return callLogger.logRequest(userId, endpoint, requestBody);
        } catch (RuntimeException e) {
            log.warn("[AgentGateway] 요청 로그 적재 실패 — 호출은 계속한다(endpoint={})", endpoint, e);
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("[AgentGateway] 요청 본문 직렬화 실패 — 로그 본문 생략", e);
            return null;
        }
    }

    private int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
