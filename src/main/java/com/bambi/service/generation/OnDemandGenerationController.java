package com.bambi.service.generation;

import com.bambi.service.auth.AuthPrincipal;
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
     */
    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<GenerationTriggerResponse> generate(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestBody(required = false) @Valid GenerationTriggerRequest request) {
        String topic = request != null ? request.normalizedTopic() : null;
        return ApiResponse.ok(onDemandGenerationService.generateForUser(principal.id(), topic));
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
