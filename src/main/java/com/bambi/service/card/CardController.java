package com.bambi.service.card;

import com.bambi.service.auth.AuthPrincipal;
import com.bambi.service.card.dto.CardResponse;
import com.bambi.service.card.dto.CardVisibilityRequest;
import com.bambi.service.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 카드 단건 조회/공개설정 (P0 + SNS).
 * 대외 식별자는 publicId(UUID). 소유자 범위는 @AuthenticationPrincipal 로 강제한다.
 * 목록은 GET /api/feed(FeedController) 가 담당한다.
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardService cardService;

    public CardController(CardService cardService) {
        this.cardService = cardService;
    }

    /** 카드 단건 — 내 카드 or PUBLIC 카드면 열람(타인 공개 카드·게스트 포함, permitAll). */
    @GetMapping("/{publicId}")
    public ApiResponse<CardResponse> get(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable String publicId) {
        Long viewerId = principal != null ? principal.id() : null;
        return ApiResponse.ok(cardService.get(viewerId, publicId));
    }

    /** 카드 공개/비공개 전환 (SNS/Week2) — 소유자만. */
    @PatchMapping("/{publicId}/visibility")
    public ApiResponse<CardResponse> changeVisibility(@AuthenticationPrincipal AuthPrincipal principal,
                                                      @PathVariable String publicId,
                                                      @Valid @RequestBody CardVisibilityRequest request) {
        return ApiResponse.ok(cardService.changeVisibility(principal.id(), publicId, request.visibility()));
    }
}
