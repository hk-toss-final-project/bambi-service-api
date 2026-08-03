package com.bambi.service.agent.publish;

import com.bambi.service.agent.publish.dto.AckRequest;
import com.bambi.service.agent.publish.dto.ClaimRequest;
import com.bambi.service.agent.AgentErrors;
import com.bambi.service.agent.publish.dto.ClaimResponse;
import com.bambi.service.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.UUID;

/**
 * {@link PublishSnapshotClient} 의 실제 구현 — RestClient 로 agent-api 발행 배치를 HTTP 호출한다.
 * service-worker 폴링 루프가 이걸로 완성 콘텐츠를 claim 하고 처리 후 ack 한다.
 * (계약 검증 2026-07-29: agent 라우터 {@code app/routers/service_worker/routes.py},
 *  테이블 {@code agent.publish_snapshots} + {@code agent.publish_attempts}.)
 *
 * <p>{@code app.agent.publish.mode=http} 일 때만 등록된다(기본 mock 은 인프로세스 큐).
 * 워커는 {@link PublishSnapshotClient} 인터페이스만 알면 되므로 구현 교체는 무변경.
 *
 * <p>claim/ack 은 워커 인프라 내부 호출이라(사용자 단위 agent 호출이 아님) AI 로그를 남기지 않는다
 * — 위키 조회 중계와 동일한 판단. 대량 폴링이라 로그가 오히려 잡음이 된다.
 */
@Component
@ConditionalOnProperty(name = "app.agent.publish.mode", havingValue = "http")
public class RestClientPublishSnapshotClient implements PublishSnapshotClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientPublishSnapshotClient.class);

    private static final String CLAIM_PATH = "/publish-snapshot-batches/claim";

    private final RestClient restClient;
    private final String internalPrefix;

    public RestClientPublishSnapshotClient(
            RestClient agentRestClient,
            @Value("${app.agent.internal-prefix}") String internalPrefix) {
        this.restClient = agentRestClient;
        this.internalPrefix = internalPrefix;
    }

    /**
     * 발행 대기 배치를 lease 걸고 가져온다. 처리할 게 없으면 agent 가 200 + items=[] 를 준다.
     * agent 연결 실패/오류는 {@link ErrorCode#AGENT_UNAVAILABLE} 로 올려 워커가 backoff 하도록 한다.
     */
    @Override
    public ClaimResponse claim(ClaimRequest request) {
        String path = internalPrefix + CLAIM_PATH;
        try {
            ClaimResponse response = restClient.post()
                    .uri(path)
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ClaimResponse.class);
            ClaimResponse safe = (response != null) ? response : ClaimResponse.empty();
            if (!safe.isEmpty()) {
                log.info("[PublishClient] claim batchId={}, items={}", safe.batchId(), safe.items().size());
            }
            return safe;

        } catch (RestClientResponseException e) {
            log.warn("[PublishClient] claim 실패 status={} body={}", e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            throw AgentErrors.unavailable(e, "발행 배치 claim 실패");
        } catch (RestClientException e) {
            log.warn("[PublishClient] claim 연결 실패: {}", e.getMessage());
            throw AgentErrors.connectFailed(e);
        }
    }

    /**
     * 처리 결과를 부분 성공 ACK. batchId 는 claim 응답 값 그대로.
     * failed 항목은 retryable·failureReason 이 있어야 agent 가 받는다({@link AckRequest.AckItem#failed}가 보장).
     */
    @Override
    public void ack(String batchId, AckRequest request) {
        String path = internalPrefix + "/publish-snapshot-batches/" + batchId + "/ack";
        try {
            restClient.post()
                    .uri(path)
                    .header("X-Request-ID", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[PublishClient] ack batchId={}, items={}", batchId, request.items().size());

        } catch (RestClientResponseException e) {
            log.warn("[PublishClient] ack 실패 batchId={} status={} body={}", batchId,
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw AgentErrors.unavailable(e, "발행 배치 ack 실패");
        } catch (RestClientException e) {
            log.warn("[PublishClient] ack 연결 실패 batchId={}: {}", batchId, e.getMessage());
            throw AgentErrors.connectFailed(e);
        }
    }
}
