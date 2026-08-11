package com.bambi.service.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * agent-api 호출용 {@link RestClient} 빈. base-url·타임아웃·내부 인증을 여기서 고정한다.
 * (Gateway 에서 직접 만들지 않고 주입받아 테스트에서 MockRestServiceServer 로 갈아끼울 수 있게 분리)
 *
 * <p><b>빈이 둘인 이유는 타임아웃 하나뿐이다.</b> base-url·인증 헤더는 같다.
 * 대부분의 agent 호출은 DB 조회 수준이라 짧게 끊어야 하지만, LLM 이 도는 호출은 그 시간 안에
 * 절대 못 끝난다. 한 빈으로 합쳐 값을 올리면 <b>가벼운 호출까지 같이 느려져</b> agent 가 죽었을 때
 * 사용자 요청이 오래 매달린다.
 */
@Configuration
public class AgentRestClientConfig {

    /** 가벼운 동기 호출용(컨텍스트 동기화·위키 조회 등). 짧게 끊는다. */
    @Bean
    public RestClient agentRestClient(
            @Value("${app.agent.base-url}") String baseUrl,
            @Value("${app.agent.internal-token:}") String internalToken,
            @Value("${app.agent.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${app.agent.read-timeout-ms}") int readTimeoutMs) {
        return build(baseUrl, internalToken, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * <b>LLM 이 도는 동기 호출용</b> — 지금은 아침 브리핑 주제 선정
     * ({@code GET /internal/v1/users/{id}/briefing-topics}) 하나가 쓴다.
     *
     * <p>연결 타임아웃은 짧은 쪽과 같이 둔다. agent 가 아예 안 떠 있으면 빨리 실패하는 게 낫고,
     * 오래 기다려야 하는 건 <b>응답</b>이지 연결이 아니다.
     *
     * <p>값의 근거는 유림님 08-10 벤치다 — <b>평균 2.2초·최대 5.07초, 12케이스 중 3개가 3초
     * 초과.</b> 실서비스는 후보가 더 많고 agent 부하도 있어 관측 최대의 3배로 잡았다(우석 08-11).
     *
     * <p>⚠️ 이 값은 스케줄러 07:00 창을 직접 먹는다. 아침 브리핑 스케줄러는 사용자를 <b>순차로</b>
     * 돌면서 사용자마다 이 호출을 한 번씩 하므로, 최악은 {@code 사용자 수 × 이 타임아웃} 이다
     * (27명 기준 약 7분 — 창 안에 든다). 사용자가 늘면 타임아웃을 늘리는 게 아니라
     * <b>루프를 병렬로 바꿔야</b> 한다.
     */
    @Bean
    public RestClient agentSelectionRestClient(
            @Value("${app.agent.base-url}") String baseUrl,
            @Value("${app.agent.internal-token:}") String internalToken,
            @Value("${app.agent.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${app.agent.selection-read-timeout-ms}") int selectionReadTimeoutMs) {
        return build(baseUrl, internalToken, connectTimeoutMs, selectionReadTimeoutMs);
    }

    private RestClient build(String baseUrl, String internalToken,
                             int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory);
        // agent 는 토큰 미설정 시 503, 불일치 시 401 을 반환한다(2026-07-30 도입).
        // 값이 비어 있으면 헤더를 붙이지 않아 무인증 로컬 agent 와도 호환된다.
        if (!internalToken.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + internalToken);
        }
        return builder.build();
    }
}
