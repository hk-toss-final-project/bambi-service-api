package com.bambi.service.generation;

import com.bambi.service.auth.AuthPrincipal;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.common.response.ApiResponse;
import com.bambi.service.generation.dto.GenerationPendingResponse;
import com.bambi.service.generation.dto.GenerationTriggerRequest;
import com.bambi.service.generation.dto.GenerationTriggerResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 즉시 리포트 생성 트리거 — 사용자가 "지금 생성"을 눌러 스케줄러를 기다리지 않고 바로 요청한다.
 * 온디맨드는 사용자 관심사 전체를 종합하는 보고서라 별도 body 없이 본인 관심사 기준으로 생성한다.
 * 실제 생성은 {@link OnDemandGenerationService} 가 {@link GenerationClient} 로 위임한다.
 */
@RestController
@RequestMapping("/api/reports")
public class OnDemandGenerationController {

    private final OnDemandGenerationService onDemandGenerationService;
    private final GenerationPendingService pendingService;

    public OnDemandGenerationController(OnDemandGenerationService onDemandGenerationService,
                                        GenerationPendingService pendingService) {
        this.onDemandGenerationService = onDemandGenerationService;
        this.pendingService = pendingService;
    }

    /**
     * 비동기 접수라 202 Accepted. body 는 job_id 를 담아 펜딩 UI 가 상태를 추적하게 한다.
     * 요청 body 는 optional — {@code {topic}} 이 오면 사용자 선택 주제(관심사 원자 반영),
     * 없으면 기존처럼 대표 관심사 자동(하위호환, 2026-08-06 계약).
     *
     * <p>{@code changeHistoryEnabled: true} 를 함께 보내면 변경점(Delta) 추적 보고서로 만든다
     * (agent-api #12 김기용). 생략하면 꺼짐이라 <b>기존 호출자의 동작은 바뀌지 않는다.</b>
     *
     * <p>{@code interestTagId} 를 보내면 관심사 깊게 파기(범주 리포트)로 만든다(2026-08-10 우석·기용).
     * 루트 주제는 agent 가 해당 관심사에서 정하므로 {@code topic} 과 배타이고, Delta 조합은
     * 계약 미정의라 거절한다 — 조용히 한쪽을 무시하면 사용자는 "켰는데 안 먹었다"를 겪는다.
     */
    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<GenerationTriggerResponse> generate(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody(required = false) @Valid GenerationTriggerRequest request) {
        String topic = request != null ? request.normalizedTopic() : null;
        boolean changeHistory = request != null && request.wantsChangeHistory();
        if (request != null && request.wantsDeepDive()) {
            if (topic != null || changeHistory) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "깊게 파기는 관심사 선택만 받습니다. topic·changeHistoryEnabled 는 함께 보낼 수 없습니다.");
            }
            return ApiResponse.ok(onDemandGenerationService.generateBundleForUser(
                    principal.id(), request.normalizedInterestTagId()));
        }
        return ApiResponse.ok(
                onDemandGenerationService.generateForUser(principal.id(), topic, changeHistory));
    }

    /**
     * 본인 최근(60분) 생성 접수 목록 — 홈 [내 보고서] "처리중" 슬롯용.
     * 완료 전환이 붙기 전이라 status 는 PENDING 만 온다(시간 창이 노출을 자름).
     * reportType(MORNING_BRIEFING|ON_DEMAND)은 접수 시점 값이라 항상 채워진다.
     */
    @GetMapping("/pending")
    public ApiResponse<List<GenerationPendingResponse>> pending(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(pendingService.listRecent(principal.id()));
    }
}
