package com.bambi.service.scrap;

import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.scrap.dto.ScrapCardResponse;
import com.bambi.service.scrap.dto.ScrapResponse;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 스크랩 도메인 (Week3 SNS) — 남의 공개(PUBLIC) 카드 + <b>본인 카드(공개 여부 무관)</b>를
 * 내 보관함에 담는다 (2026-08-11 확장 — 본인 PRIVATE 보고서 즐겨찾기, 여진 요청·우석 확정).
 * 담기/취소는 멱등(좋아요 패턴). 목록은 "아직 PUBLIC 인 카드 + 내 카드"만
 * (남의 카드가 비공개로 전환되면 숨김 — 내 카드는 내 공개설정과 무관하게 항상 보인다).
 */
@Service
public class ScrapService {

    private static final String PUBLIC = "PUBLIC";

    private final ScrapRepository scrapRepository;
    private final CardRepository cardRepository;
    private final UserRepository userRepository;
    private final ScrapWikiOutboxService wikiOutbox;

    public ScrapService(ScrapRepository scrapRepository,
                        CardRepository cardRepository,
                        UserRepository userRepository,
                        ScrapWikiOutboxService wikiOutbox) {
        this.scrapRepository = scrapRepository;
        this.cardRepository = cardRepository;
        this.userRepository = userRepository;
        this.wikiOutbox = wikiOutbox;
    }

    @Transactional
    public ScrapResponse scrap(Long userId, String cardPublicId) {
        Card card = resolveAliveCard(cardPublicId);
        // 공개(PUBLIC) 카드 또는 **본인 카드**(공개 여부 무관)만 스크랩 가능(2026-08-11 확장).
        // 타인의 비공개/없음은 존재 노출 없이 404 — 기존 프라이버시 규칙 그대로.
        if (!PUBLIC.equals(card.getVisibility()) && !card.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");
        }
        int inserted = scrapRepository.insertIgnore(userId, card.getId());   // 멱등
        if (inserted == 1 && hasExternalContent(card)) {
            wikiOutbox.enqueueAdd(userId, card.getId(), card.getExternalContentId());
        }
        return new ScrapResponse(true);
    }

    @Transactional
    public ScrapResponse unscrap(Long userId, String cardPublicId) {
        // 취소는 PUBLIC 검사를 하지 않는다: 담은 뒤 소유자가 비공개로 바꿔도 취소할 수 있어야 한다.
        // deleteRelation 은 없어도 0건이라 멱등하게 안전하다.
        Card card = resolveAliveCard(cardPublicId);
        int deleted = scrapRepository.deleteRelation(userId, card.getId());
        if (deleted == 1 && hasExternalContent(card)) {
            wikiOutbox.enqueueRemove(userId, card.getId(), card.getExternalContentId());
        }
        return new ScrapResponse(false);
    }

    /**
     * 내 스크랩 목록 — "아직 PUBLIC 인 카드 + 내 카드" 스크랩 최신순.
     * 남의 카드는 비공개 전환 시 숨기고(기존 규칙), 내 카드는 공개설정과 무관하게 보인다(08-11 확장).
     * 작성자는 1 IN 쿼리로 배치 로딩(N+1 차단).
     */
    @Transactional(readOnly = true)
    public List<ScrapCardResponse> myScraps(Long userId) {
        List<Card> cards = scrapRepository.findMyScrappedVisibleCards(userId);
        if (cards.isEmpty()) {
            return List.of();
        }
        List<Long> authorIds = cards.stream().map(Card::getUserId).distinct().toList();
        Map<Long, User> authors = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return cards.stream()
                .map(c -> ScrapCardResponse.from(c, authors.get(c.getUserId())))
                .toList();
    }

    /** publicId 로 살아있는 카드 조회. 형식 오류/없음은 존재 노출 없이 404. (PUBLIC 여부는 호출부 판단) */
    private Card resolveAliveCard(String cardPublicId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(cardPublicId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");
        }
        return cardRepository.findByPublicIdAndDeletedAtIsNull(uuid)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다."));
    }

    private boolean hasExternalContent(Card card) {
        return card.getExternalContentId() != null && !card.getExternalContentId().isBlank();
    }
}
