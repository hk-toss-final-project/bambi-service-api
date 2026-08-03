package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentContextRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * agent 컨텍스트 upsert 요청의 <b>와이어 포맷 계약 테스트</b>.
 *
 * <p>{@link RestClientAgentGatewayTest}(에러 매핑 검증)와 달리, 여기서는 우리가 agent 로
 * 실제로 <b>내보내는 요청의 모양</b>이 계약(docs/agent-contract.md §4,
 * agent {@code UserContextUpsertRequest})과 일치하는지를 잠근다. 메서드·경로·본문 키 이름을
 * 고정해, 누군가 DTO 의 {@code @JsonProperty} 를 지우거나 필드를 camelCase 로 바꾸면
 * 이 테스트가 먼저 깨지게 한다.
 */
class AgentContextContractTest {

    private static final String CONTEXT_URL = "http://agent.local/internal/v1/users/7/context";

    private MockRestServiceServer server;
    private RestClientAgentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.local");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        AgentCallLogger callLogger = mock(AgentCallLogger.class);
        when(callLogger.logRequest(any(), any(), any())).thenReturn(1L);

        gateway = new RestClientAgentGateway(client, "/internal/v1", callLogger, new ObjectMapper());
    }

    @Test
    @DisplayName("PUT /internal/v1/users/{id}/context — JSON 본문으로 호출한다")
    void method_path_contentType() {
        server.expect(requestTo(CONTEXT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header("X-Request-ID", org.hamcrest.Matchers.not(org.hamcrest.Matchers.blankOrNullString())))
                .andRespond(withSuccess("{\"context_version\":1}", MediaType.APPLICATION_JSON));

        gateway.syncUserContext(7, AgentContextRequest.forVersion(1));

        server.verify();
    }

    @Test
    @DisplayName("본문 키는 계약대로 snake_case 6개 필드 — 최초 가입 기본값")
    void body_snakeCaseFields() {
        server.expect(requestTo(CONTEXT_URL))
                .andExpect(jsonPath("$.context_version").value(1))
                .andExpect(jsonPath("$.plan").value("free"))
                .andExpect(jsonPath("$.preferred_language").value("ko"))
                .andExpect(jsonPath("$.personalization_enabled").value(true))
                .andExpect(jsonPath("$.blocked_interest_ids").value(empty()))
                .andExpect(jsonPath("$.blocked_source_ids").value(empty()))
                .andRespond(withSuccess("{\"context_version\":1}", MediaType.APPLICATION_JSON));

        gateway.syncUserContext(7, AgentContextRequest.forVersion(1));

        server.verify();
    }

    @Test
    @DisplayName("camelCase 키가 새어나가면 안 된다 (@JsonProperty 제거 회귀 방지)")
    void body_noCamelCaseLeak() {
        server.expect(requestTo(CONTEXT_URL))
                .andExpect(jsonPath("$.contextVersion").doesNotExist())
                .andExpect(jsonPath("$.preferredLanguage").doesNotExist())
                .andExpect(jsonPath("$.personalizationEnabled").doesNotExist())
                .andExpect(jsonPath("$.blockedInterestIds").doesNotExist())
                .andExpect(jsonPath("$.blockedSourceIds").doesNotExist())
                .andRespond(withSuccess("{\"context_version\":1}", MediaType.APPLICATION_JSON));

        gateway.syncUserContext(7, AgentContextRequest.forVersion(1));

        server.verify();
    }
}
