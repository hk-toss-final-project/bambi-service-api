package com.bambi.service.generation;

import com.bambi.service.agent.AgentErrors;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * {@link GenerationClient} 의 실제 구현 — RestClient 로 agent-api 에 생성 Job 을 등록한다
 * (계약: {@code POST /internal/v1/users/{id}/generations} → 202 + job_id, 가이드 §3.4).
 * 스케줄러가 지정 시각에 사용자별로 호출하고, 완성 결과는 별도 service-worker 발행 폴링으로 들어온다.
 *
 * <p>{@code app.agent.generation.mode=http} 일 때만 등록된다(기본 mock 은 로그만 남기는 스텁).
 * 스케줄러는 {@link GenerationClient} 인터페이스만 알면 되므로 구현 교체는 무변경.
 *
 * <p>발행 claim/ack 과 같은 워커성 대량 호출(매일 전원 배치)이라 AI 로그는 남기지 않는다.
 * agent 연결 실패/오류는 {@link ErrorCode#AGENT_UNAVAILABLE} 로 올려 스케줄러가 그 사용자만 건너뛰게 한다
 * — 컨텍스트 없는 사용자의 {@code 409 USER_CONTEXT_REQUIRED} 도 여기에 포함된다.
 */
@Component
@ConditionalOnProperty(name = "app.agent.generation.mode", havingValue = "http")
public class RestClientGenerationClient implements GenerationClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientGenerationClient.class);

    private final RestClient restClient;
    private final String internalPrefix;

    public RestClientGenerationClient(
            RestClient agentRestClient,
            @Value("${app.agent.internal-prefix}") String internalPrefix) {
        this.restClient = agentRestClient;
        this.internalPrefix = internalPrefix;
    }

    @Override
    public void requestGeneration(long userId, GenerationRequest request) {
        String path = internalPrefix + "/users/" + userId + "/generations";
        try {
            ResponseEntity<String> resp = restClient.post()
                    .uri(path)
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(String.class);
            // 202 Accepted 가 정상 — Job 등록만 확인한다(결과는 발행 폴링으로 수령).
            // 매일 전 사용자 대상 호출이라 info 는 한 줄 요약만, 무거운 응답 body 는 debug 로 내린다.
            log.info("[GenerationClient] 생성 요청 userId={}, idempotencyKey={}, status={}",
                    userId, request.idempotencyKey(), resp.getStatusCode().value());
            log.debug("[GenerationClient] 생성 응답 body userId={}: {}", userId, resp.getBody());

        } catch (RestClientResponseException e) {
            log.warn("[GenerationClient] 생성 요청 실패 userId={} status={} body={}",
                    userId, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw AgentErrors.unavailable(e, "agent 생성 요청 실패");
        } catch (RestClientException e) {
            log.warn("[GenerationClient] 생성 요청 연결 실패 userId={}: {}", userId, e.getMessage());
            throw AgentErrors.connectFailed(e);
        }
    }
}
