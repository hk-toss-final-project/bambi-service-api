package com.bambi.service.wiki;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.wiki.dto.BriefingTopicsSelection;
import com.bambi.service.wiki.dto.WikiDocumentDetailResponse;
import com.bambi.service.wiki.dto.WikiDocumentsResponse;
import com.bambi.service.wiki.dto.WikiGraphResponse;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import com.bambi.service.wiki.dto.WikiResetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link AgentWikiClient} — agent snake_case 응답을 camelCase 로 읽고 topic→tag 리네임하는지,
 * 없는 사용자(404)를 빈 결과로 정규화하는지 MockRestServiceServer 로 검증한다.
 */
class AgentWikiClientTest {

    private MockRestServiceServer server;
    private AgentWikiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent.local");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        // 운영에서는 주제 선정만 타임아웃이 긴 별도 빈을 쓴다. 여기서는 같은 Mock 서버에
        // 물려 두 경로가 같은 규약(경로·404 정규화)을 지키는지 한 번에 검증한다.
        client = new AgentWikiClient(restClient, restClient, "/internal/v1");
    }

    @Test
    @DisplayName("관심 조회: topic→tag·interest_id→tagId·document_ids→documentIds 로 매핑한다")
    void getTagsMapsSnakeAndRenamesTopic() {
        String agentBody = """
                {"profile_id":"p1","version":1,"status":"active","calculated_at":"2026-07-22T03:15:18Z",
                 "interests":[{"interest_id":"i1","topic":"원/달러 환율","category":null,"score":1.0,
                   "confidence":0.7,"document_ids":["d1","d2"],"evidence":{"weight":5.0,"reasons":["title"]}}]}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/users/7/interests"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        WikiTagsResponse resp = client.getTags(7);

        assertThat(resp.profileId()).isEqualTo("p1");
        assertThat(resp.tags()).hasSize(1);
        assertThat(resp.tags().get(0).tag()).isEqualTo("원/달러 환율"); // topic → tag
        assertThat(resp.tags().get(0).tagId()).isEqualTo("i1");         // interest_id → tagId
        assertThat(resp.tags().get(0).documentIds()).containsExactly("d1", "d2");
        assertThat(resp.tags().get(0).category()).isNull();
    }

    @Test
    @DisplayName("활성 Profile 없는 사용자(agent 404)는 빈 태그 목록으로 정규화한다")
    void getTagsNotFoundReturnsEmpty() {
        server.expect(requestTo("http://agent.local/internal/v1/users/7/interests"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"code\":\"INTEREST_PROFILE_NOT_FOUND\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        WikiTagsResponse resp = client.getTags(7);

        assertThat(resp.tags()).isEmpty();
        assertThat(resp.status()).isEqualTo("empty");
    }

    @Test
    @DisplayName("문서 조회: snake_case 를 읽어 그대로 담는다(schema 제외는 서비스 몫)")
    void getDocumentsReadsSnake() {
        String agentBody = """
                {"total":2,"items":[
                  {"document_id":"c1","document_kind":"concept","title":"개인 지식 그래프","summary":"요약",
                   "domain":"other","source_count":1,"updated_at":"2026-07-22T03:15:18Z"},
                  {"document_id":"s1","document_kind":"schema","title":"Schema","summary":null,
                   "domain":null,"source_count":0,"updated_at":"2026-07-22T03:15:18Z"}]}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/users/7/wiki/documents"))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        WikiDocumentsResponse resp = client.getDocuments(7);

        assertThat(resp.total()).isEqualTo(2);
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).documentId()).isEqualTo("c1");
        assertThat(resp.items().get(0).documentKind()).isEqualTo("concept");
    }

    @Test
    @DisplayName("Graph 조회: 사용자 식별 정보와 Markdown은 버리고 Node·Edge 계약을 camelCase로 매핑한다")
    void getGraphReadsVisualizationContract() {
        String agentBody = """
                {"user_id":"7","namespace_key":"user/7","wiki_version":3,"generated_at":"2026-07-22T03:15:18Z",
                 "stats":{"node_count":2,"edge_count":1,"entity_count":1,"concept_count":1,"orphan_count":0},
                 "nodes":[{"id":"n1","document_kind":"entity","document_key":"obsidian","title":"Obsidian",
                   "subtype":"product","summary":"지식 관리 도구","aliases":["옵시디언"],"file_path":"entities/obsidian.md",
                   "version":2,"updated_at":"2026-07-22T03:15:18Z","markdown":"내부 본문","degree":1}],
                 "edges":[{"id":"e1","source":"n1","target":"n2","relation_type":"applies_concept","metadata":{}}]}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/users/7/wiki/graph"))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        WikiGraphResponse resp = client.getGraph(7);

        assertThat(resp.wikiVersion()).isEqualTo(3);
        assertThat(resp.stats().nodeCount()).isEqualTo(2);
        assertThat(resp.nodes().get(0).documentKind()).isEqualTo("entity");
        assertThat(resp.nodes().get(0).filePath()).isEqualTo("entities/obsidian.md");
        assertThat(resp.edges().get(0).relationType()).isEqualTo("applies_concept");
    }

    @Test
    @DisplayName("문서 상세 조회: 원본 URL과 관련 Node를 보존한다")
    void getDocumentReadsSourcesAndRelations() {
        String agentBody = """
                {"document_id":"n1","document_version_id":"v2","document_kind":"entity","document_key":"obsidian",
                 "file_path":"entities/obsidian.md","domain":"product","title":"Obsidian","summary":"지식 관리 도구",
                 "version":2,"source_count":1,"updated_at":"2026-07-22T03:15:18Z","markdown":"## 설명",
                 "sources":[{"source_document_id":"s1","source_document_version_id":"sv1","source_type":"url",
                   "source_version":1,"title":"공식 문서","canonical_url":"https://obsidian.md","relation_type":"derived_from"}],
                 "relations":[{"direction":"outgoing","related_document_id":"n2","related_document_kind":"concept",
                   "related_document_key":"linked-notes","related_title":"연결 노트","relation_type":"applies_concept","metadata":{}}]}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/users/7/wiki/documents/n1"))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        WikiDocumentDetailResponse resp = client.getDocument(7, "n1");

        assertThat(resp.documentVersionId()).isEqualTo("v2");
        assertThat(resp.sources().get(0).canonicalUrl()).isEqualTo("https://obsidian.md");
        assertThat(resp.relations().get(0).relatedDocumentId()).isEqualTo("n2");
    }

    @Test
    @DisplayName("문서 상세 조회: Agent 404는 사용자용 NOT_FOUND로 변환한다")
    void getDocumentMapsNotFound() {
        server.expect(requestTo("http://agent.local/internal/v1/users/7/wiki/documents/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getDocument(7, "missing"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("Wiki 초기화: 인증 사용자 경로에 DELETE하고 반영 건수를 매핑한다")
    void resetMapsAccountScopedCounts() {
        String agentBody = """
                {"user_id":"7","reset_document_count":3,"reset_relation_count":2,
                 "unsearchable_chunk_count":5,"deleted_source_document_count":4,
                 "deleted_source_version_count":6,"redacted_source_event_count":4,
                 "retired_wiki_version_count":1,
                 "retired_interest_profile_count":1,"cancelled_job_count":1,
                 "reset_at":"2026-08-10T00:00:00Z","request_id":"request-1"}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/users/7/wiki"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        WikiResetResponse response = client.reset(7L);

        assertThat(response.userId()).isEqualTo("7");
        assertThat(response.resetDocumentCount()).isEqualTo(3);
        assertThat(response.unsearchableChunkCount()).isEqualTo(5);
        assertThat(response.deletedSourceDocumentCount()).isEqualTo(4);
        assertThat(response.deletedSourceVersionCount()).isEqualTo(6);
        assertThat(response.redactedSourceEventCount()).isEqualTo(4);
        assertThat(response.cancelledJobCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("아침 주제 선정: snake_case 응답을 읽고 limit 을 질의로 붙인다")
    void getBriefingTopicsMapsSnakeCaseAndPassesLimit() {
        String agentBody = """
                {"user_id":"7","topics":["코스닥","폭염","웹툰"],
                 "reason":"최근 저장한 글이 시장·날씨에 몰려 있다","candidate_count":19}
                """;
        server.expect(requestTo("http://agent.local/internal/v1/users/7/briefing-topics?limit=3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(agentBody, MediaType.APPLICATION_JSON));

        BriefingTopicsSelection selection = client.getBriefingTopics(7L, 3);

        assertThat(selection.normalizedTopics()).containsExactly("코스닥", "폭염", "웹툰");
        assertThat(selection.candidateCount()).isEqualTo(19);
        assertThat(selection.reasonOrEmpty()).isEqualTo("최근 저장한 글이 시장·날씨에 몰려 있다");
    }

    @Test
    @DisplayName("아침 주제 선정: 위키 없는 사용자(404)는 오류가 아니라 빈 결과다")
    void getBriefingTopicsReturnsEmptyOnNotFound() {
        // 신규 사용자에게 500 을 올리면 스케줄러가 그 사용자를 실패로 처리한다.
        // 폴백(등록 관심사)으로 넘어가야 하므로 빈 결과로 정규화한다 — getTags 와 같은 정책.
        server.expect(requestTo("http://agent.local/internal/v1/users/7/briefing-topics?limit=3"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.getBriefingTopics(7L, 3).normalizedTopics()).isEmpty();
    }

    @Test
    @DisplayName("아침 주제 선정: agent 오류(503)는 그대로 올린다 — 호출부가 폴백을 정한다")
    void getBriefingTopicsRaisesOnServerError() {
        server.expect(requestTo("http://agent.local/internal/v1/users/7/briefing-topics?limit=3"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getBriefingTopics(7L, 3))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.AGENT_UNAVAILABLE);
    }
}
