package com.bambi.service.wiki;

import com.bambi.service.auth.AuthPrincipal;
import com.bambi.service.common.response.ApiResponse;
import com.bambi.service.wiki.dto.WikiDocumentDetailResponse;
import com.bambi.service.wiki.dto.WikiDocumentsResponse;
import com.bambi.service.wiki.dto.WikiGraphResponse;
import com.bambi.service.wiki.dto.WikiTagsResponse;
import com.bambi.service.wiki.dto.WikiTopNodesResponse;
import com.bambi.service.wiki.dto.WikiResetResponse;
import com.bambi.service.wiki.dto.WikiBuildStatusResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 개인 Wiki 조회 중계 (관심사 · LLM Wiki 화면). 전부 인증 필수, 사용자 범위는 인증 주체로 강제한다.
 *
 * <p>agent 자동 추출 관심은 {@code /tags}(topic→tag 리네임), 저장 자료는 {@code /documents}(schema 제외),
 * 사용자용 LLM Wiki는 {@code /graph}와 {@code /documents/{documentId}}로 중계한다.
 * 사용자가 직접 만드는 관심사는 여기가 아니라 {@code /api/interests} 다.
 */
@RestController
@RequestMapping("/api/wiki")
public class WikiRelayController {

    private final WikiRelayService wikiRelayService;
    private final WikiBuildOperationService wikiBuildOperationService;

    public WikiRelayController(
            WikiRelayService wikiRelayService,
            WikiBuildOperationService wikiBuildOperationService) {
        this.wikiRelayService = wikiRelayService;
        this.wikiBuildOperationService = wikiBuildOperationService;
    }

    @GetMapping("/tags")
    public ApiResponse<WikiTagsResponse> tags(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(wikiRelayService.tags(principal.id()));
    }

    /**
     * 발견 관심사 숨기기 (2026-08-11 우석) — 이름 기준, 멱등.
     * agent 태그 id 는 위키 재계산마다 바뀌므로 id 가 아니라 이름을 키로 쓴다.
     */
    @PostMapping("/tags/blocks")
    public ApiResponse<Void> blockTag(@AuthenticationPrincipal AuthPrincipal principal,
                                      @RequestBody @Valid BlockTagRequest request) {
        wikiRelayService.blockTag(principal.id(), request.name());
        return ApiResponse.ok(null);
    }

    /** 숨김 해제 — 멱등. 되돌리기(다시 보이게)에 쓴다. */
    @DeleteMapping("/tags/blocks")
    public ApiResponse<Void> unblockTag(@AuthenticationPrincipal AuthPrincipal principal,
                                        @RequestBody @Valid BlockTagRequest request) {
        wikiRelayService.unblockTag(principal.id(), request.name());
        return ApiResponse.ok(null);
    }

    /** 숨김 대상 태그 이름. 정규화(소문자·trim)는 서비스가 한다. */
    public record BlockTagRequest(@NotBlank @Size(max = 200) String name) {
    }

    @GetMapping("/documents")
    public ApiResponse<WikiDocumentsResponse> documents(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(wikiRelayService.documents(principal.id()));
    }

    @GetMapping("/graph")
    public ApiResponse<WikiGraphResponse> graph(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(wikiRelayService.graph(principal.id()));
    }

    @GetMapping("/build-status")
    public ApiResponse<WikiBuildStatusResponse> buildStatus(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(wikiBuildOperationService.statusFor(principal.id()));
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<WikiDocumentDetailResponse> document(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable String documentId) {
        return ApiResponse.ok(wikiRelayService.document(principal.id(), documentId));
    }

    @GetMapping("/graph/top-nodes")
    public ApiResponse<WikiTopNodesResponse> topNodes(@AuthenticationPrincipal AuthPrincipal principal,
                                                      @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(wikiRelayService.topNodes(principal.id(), limit));
    }

    @DeleteMapping
    public ApiResponse<WikiResetResponse> reset(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(wikiRelayService.reset(principal.id()));
    }
}
