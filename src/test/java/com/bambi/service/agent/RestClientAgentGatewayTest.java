package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentClippingRequest;
import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.agent.dto.AgentUrlSourceRequest;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link RestClientAgentGateway} 단위 테스트 — MockRestServiceServer 로 agent 응답을 흉내낸다.
 * 성공/STALE/5xx 각 경우의 에러 매핑과 AI 로그 적재 호출을 검증한다.
 */
class RestClientAgentGatewayTest {

    private static final String CONTEXT_URL = "http://agent.local/internal/v1/users/7/context";
    private static final String CLIPPING_URL = "http://agent.local/internal/v1/users/7/wiki-sources/clippings";
    private static final String URL_SOURCE_URL = "http://agent.local/internal/v1/users/7/wiki-sources/urls";

    private MockRestServiceServer server;
    private AgentCallLogger callLogger;
    private RestClientAgentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.local");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        callLogger = mock(AgentCallLogger.class);
        when(callLogger.logRequest(any(), any(), any())).thenReturn(1L);

        gateway = new RestClientAgentGateway(client, "/internal/v1", callLogger, new ObjectMapper());
    }

    @Test
    @DisplayName("성공(2xx)이면 요청/응답 로그를 남기고 통과한다")
    void success() {
        server.expect(requestTo(CONTEXT_URL))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("{\"context_version\":1}", MediaType.APPLICATION_JSON));

        gateway.syncUserContext(7, AgentContextRequest.forVersion(1));

        server.verify();
        verify(callLogger).logRequest(eq(7L), eq("/internal/v1/users/7/context"), any());
        verify(callLogger).logResponse(eq(1L), eq(200), anyInt(), any());
    }

    @Test
    @DisplayName("STALE_CONTEXT_VERSION(409)은 '이미 최신'이라 오류가 아니라 성공 처리한다")
    void staleContextVersionIsNotError() {
        server.expect(requestTo(CONTEXT_URL))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .body("{\"code\":\"STALE_CONTEXT_VERSION\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        gateway.syncUserContext(7, AgentContextRequest.forVersion(1)); // 예외 없이 통과

        verify(callLogger).logResponse(eq(1L), eq(409), anyInt(), any());
    }

    @Test
    @DisplayName("agent 5xx는 AGENT_UNAVAILABLE로 변환하고 응답 로그를 남긴다")
    void agentServerErrorMapsToAgentUnavailable() {
        server.expect(requestTo(CONTEXT_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ApiException ex = catchThrowableOfType(
                () -> gateway.syncUserContext(7, AgentContextRequest.forVersion(1)),
                ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
        verify(callLogger).logResponse(eq(1L), eq(503), anyInt(), any());
    }

    @Test
    @DisplayName("클리핑 중계는 clippings 경로로 POST하고 202를 성공으로 로그한다")
    void relayClippingAcceptsAsync() {
        server.expect(requestTo(CLIPPING_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .body("{\"job_id\":\"j-1\",\"status\":\"queued\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        gateway.relayClipping(7, AgentClippingRequest.of("bookmark-42", "https://ex.com/a", "제목", "본문"));

        server.verify();
        verify(callLogger).logRequest(eq(7L), eq("/internal/v1/users/7/wiki-sources/clippings"), any());
        verify(callLogger).logResponse(eq(1L), eq(202), anyInt(), any());
    }

    @Test
    @DisplayName("클리핑 중계 5xx는 AGENT_UNAVAILABLE로 변환한다")
    void relayClippingServerErrorMapsToAgentUnavailable() {
        server.expect(requestTo(CLIPPING_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ApiException ex = catchThrowableOfType(
                () -> gateway.relayClipping(7, AgentClippingRequest.of("bookmark-42", "https://ex.com/a", "제목", "본문")),
                ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
        verify(callLogger).logResponse(eq(1L), eq(503), anyInt(), any());
    }

    @Test
    @DisplayName("URL 원천 중계는 urls 경로로 POST하고 202를 성공으로 로그한다")
    void relayUrlSourceAcceptsAsync() {
        server.expect(requestTo(URL_SOURCE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .body("{\"job_id\":\"j-2\",\"status\":\"queued\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        gateway.relayUrlSource(7, AgentUrlSourceRequest.of("bookmark-42", "https://ex.com/a"));

        server.verify();
        verify(callLogger).logRequest(eq(7L), eq("/internal/v1/users/7/wiki-sources/urls"), any());
        verify(callLogger).logResponse(eq(1L), eq(202), anyInt(), any());
    }
}
