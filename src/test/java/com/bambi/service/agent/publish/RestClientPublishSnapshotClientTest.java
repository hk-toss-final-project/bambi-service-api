package com.bambi.service.agent.publish;

import com.bambi.service.agent.publish.dto.AckRequest;
import com.bambi.service.agent.publish.dto.ClaimRequest;
import com.bambi.service.agent.publish.dto.ClaimResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestClientPublishSnapshotClient} — claim/ack 와이어 포맷과 에러 매핑을 MockRestServiceServer 로 잠근다.
 * 검증: (1) claim 요청 snake_case·경로·응답 파싱 (2) 처리할 것 없을 때 빈 응답 (3) ack 경로·본문(실패 항목 필드)
 *      (4) agent 오류 → AGENT_UNAVAILABLE.
 */
class RestClientPublishSnapshotClientTest {

    private MockRestServiceServer server;
    private RestClientPublishSnapshotClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.local");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestClientPublishSnapshotClient(builder.build(), "/internal/v1");
    }

    @Test
    @DisplayName("claim: snake_case 로 POST 하고 응답(스냅샷 페이로드)을 파싱한다")
    void claimSendsSnakeAndParsesPayload() {
        String agentBody = """
                {"batch_id":"batch-1","worker_id":"service-worker-01",
                 "lease_expires_at":"2026-07-30T02:00:00Z",
                 "items":[
                   {"content_id":"bookmark-9","user_id":"23","version":2,"snapshot_hash":"h9",
                    "title":"심화 브리핑","summary":"요약","body":"본문 전체",
                    "citations":[{"title":"출처","url":"https://ex.com"}],
                    "tags":["코스피"],
                    "content_tags":["외국인 수급","반도체주"],
                    "created_at":"2026-07-30T01:00:00Z"}]}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/publish-snapshot-batches/claim"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.worker_id").value("service-worker-01"))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.lease_seconds").value(120))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        ClaimResponse resp = client.claim(new ClaimRequest("service-worker-01", 50, 120));

        assertThat(resp.isEmpty()).isFalse();
        assertThat(resp.batchId()).isEqualTo("batch-1");
        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).contentId()).isEqualTo("bookmark-9");
        assertThat(resp.items().get(0).userId()).isEqualTo("23");   // agent 계약상 문자열
        assertThat(resp.items().get(0).version()).isEqualTo(2);
        assertThat(resp.items().get(0).body()).isEqualTo("본문 전체");
        assertThat(resp.items().get(0).citations()).hasSize(1);
        assertThat(resp.items().get(0).citations().get(0).url()).isEqualTo("https://ex.com");
        assertThat(resp.items().get(0).tags()).containsExactly("코스피");   // topic 에코 파싱
        assertThat(resp.items().get(0).contentTags()).containsExactly("외국인 수급", "반도체주");   // content_tags 파싱
        assertThat(resp.items().get(0).interestTags()).containsExactly("외국인 수급", "반도체주");   // 노출용은 content_tags 우선
        // report_type 미도착(소라 게이트웨이 롤아웃 전) 스냅샷 → null 관용 파싱, 발행 안 깨짐
        assertThat(resp.items().get(0).reportType()).isNull();
        assertThat(resp.items().get(0).normalizedReportType()).isNull();
    }

    @Test
    @DisplayName("claim: report_type 이 오면 그대로 파싱한다 (MORNING_BRIEFING|ON_DEMAND)")
    void claimParsesReportTypeWhenPresent() {
        String agentBody = """
                {"batch_id":"batch-2","worker_id":"w1","lease_expires_at":"2026-08-06T02:00:00Z",
                 "items":[
                   {"content_id":"gen-1","user_id":"23","version":1,"snapshot_hash":"h1",
                    "title":"아침 브리핑","summary":"요약","body":"본문",
                    "citations":[],"tags":[],"content_tags":["반도체"],
                    "report_type":"MORNING_BRIEFING"}]}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/publish-snapshot-batches/claim"))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        ClaimResponse resp = client.claim(new ClaimRequest("w1", 50, 120));

        assertThat(resp.items().get(0).reportType()).isEqualTo("MORNING_BRIEFING");
        assertThat(resp.items().get(0).normalizedReportType()).isEqualTo("MORNING_BRIEFING");
    }

    @Test
    @DisplayName("claim: 처리할 것 없으면 200 + items=[] 를 빈 응답으로 받는다")
    void claimEmptyBatch() {
        server.expect(requestTo("http://agent.local/internal/v1/publish-snapshot-batches/claim"))
                .andRespond(withSuccess(
                        "{\"batch_id\":null,\"worker_id\":\"w1\",\"lease_expires_at\":null,\"items\":[]}",
                        MediaType.APPLICATION_JSON));

        ClaimResponse resp = client.claim(new ClaimRequest("w1", 50, 120));

        assertThat(resp.isEmpty()).isTrue();
        assertThat(resp.batchId()).isNull();
    }

    @Test
    @DisplayName("claim: agent 오류(503)는 AGENT_UNAVAILABLE 로 변환한다")
    void claimServerErrorMapsToUnavailable() {
        server.expect(requestTo("http://agent.local/internal/v1/publish-snapshot-batches/claim"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.claim(new ClaimRequest("w1", 50, 120)))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }

    @Test
    @DisplayName("ack: batchId 경로로 POST 하고 published/failed 항목 본문을 싣는다")
    void ackSendsBatchResult() {
        server.expect(requestTo("http://agent.local/internal/v1/publish-snapshot-batches/batch-1/ack"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.worker_id").value("service-worker-01"))
                .andExpect(jsonPath("$.items[0].content_id").value("c1"))
                .andExpect(jsonPath("$.items[0].status").value("published"))
                // published 항목엔 retryable/failure_reason 을 싣지 않는다(@JsonInclude NON_NULL)
                .andExpect(jsonPath("$.items[0].retryable").doesNotExist())
                .andExpect(jsonPath("$.items[1].status").value("failed"))
                .andExpect(jsonPath("$.items[1].retryable").value(true))
                .andExpect(jsonPath("$.items[1].failure_reason").value("service-db timeout"))
                .andRespond(withSuccess(
                        "{\"batch_id\":\"batch-1\",\"published_count\":1,\"retry_scheduled_count\":1,"
                                + "\"failed_count\":0,\"results\":[],\"acknowledged_at\":\"2026-07-30T02:05:00Z\"}",
                        MediaType.APPLICATION_JSON));

        AckRequest request = new AckRequest("service-worker-01", List.of(
                AckRequest.AckItem.published("c1", 1, "h1"),
                AckRequest.AckItem.failed("c2", 1, "h2", true, "service-db timeout")));

        client.ack("batch-1", request);

        server.verify();
    }

    @Test
    @DisplayName("ack: agent 오류(500)는 AGENT_UNAVAILABLE 로 변환한다")
    void ackServerErrorMapsToUnavailable() {
        server.expect(requestTo("http://agent.local/internal/v1/publish-snapshot-batches/batch-1/ack"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        AckRequest request = new AckRequest("w1", List.of(
                AckRequest.AckItem.published("c1", 1, "h1")));

        assertThatThrownBy(() -> client.ack("batch-1", request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }
}
