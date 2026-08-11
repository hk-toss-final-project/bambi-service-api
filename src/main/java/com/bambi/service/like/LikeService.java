package com.bambi.service.like;

import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.like.dto.LikeResponse;
import com.bambi.service.notification.NotificationService;
import com.bambi.service.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 좋아요 도메인 (SNS/Week2). 공개(PUBLIC) 카드에만 좋아요할 수 있다.
 * 좋아요/취소는 멱등 — 낙관적 업데이트 재시도에 안전.
 * 남의 비공개 카드는 존재 노출 없이 404(공개피드에 없는 카드).
 */
@Service
public class LikeService {

    private static final String PUBLIC = "PUBLIC";

    private static final Logger log = LoggerFactory.getLogger(LikeService.class);

    private final LikeRepository likeRepository;
    private final CardRepository cardRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public LikeService(LikeRepository likeRepository, CardRepository cardRepository,
                       UserRepository userRepository, NotificationService notificationService) {
        this.likeRepository = likeRepository;
        this.cardRepository = cardRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public LikeResponse like(Long userId, String cardPublicId) {
        Card card = resolveAliveCard(cardPublicId);
        // 좋아요는 공개(PUBLIC) 카드에만. 비공개/없음은 존재 노출 없이 404.
        if (!PUBLIC.equals(card.getVisibility())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");
        }
        int inserted = likeRepository.insertIgnore(userId, card.getId());   // 멱등
        // 신규 좋아요 + 남의 카드일 때만 작성자에게 알림(2026-08-11 여진 요청).
        // 본인 카드 좋아요는 제외, 재좋아요(0건)는 호출도 안 한다(event_key UNIQUE 가 이중 방어).
        if (inserted > 0 && !card.getUserId().equals(userId)) {
            notifyLiked(userId, card);
        }
        return new LikeResponse(true, likeRepository.countByCardId(card.getId()));
    }

    /** 좋아요 알림 — 생성 실패가 좋아요 자체를 깨지 않게 삼킨다(FOLLOW 알림과 동일 정책). */
    private void notifyLiked(Long likerId, Card card) {
        try {
            userRepository.findById(likerId).ifPresent(liker ->
                    notificationService.notifyLiked(
                            card.getUserId(), likerId, liker.getDisplayName(),
                            card.getId(), card.getPublicId(), card.getTitle()));
        } catch (RuntimeException e) {
            log.warn("[Like] 좋아요 알림 생성 실패 — 좋아요는 정상 처리(likerId={}, cardId={})",
                    likerId, card.getId(), e);
        }
    }

    @Transactional
    public LikeResponse unlike(Long userId, String cardPublicId) {
        // 취소는 PUBLIC 검사를 하지 않는다: 좋아요한 뒤 소유자가 비공개로 바꿔도 취소할 수 있어야 한다.
        // deleteRelation 은 없어도 0건이라 멱등하게 안전하다.
        Card card = resolveAliveCard(cardPublicId);
        likeRepository.deleteRelation(userId, card.getId());
        return new LikeResponse(false, likeRepository.countByCardId(card.getId()));
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
}
