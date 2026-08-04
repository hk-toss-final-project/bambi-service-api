package com.bambi.service.card;

import com.bambi.service.card.dto.CardResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.like.LikeRepository;
import com.bambi.service.report.Report;
import com.bambi.service.report.ReportRepository;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 카드 조회 서비스 (읽기 전용).
 * 소유자 범위는 userId 로 강제하고, 대외 식별자 publicId(UUID) 로만 단건을 찾는다.
 * (피드 목록 조회는 FeedService 가 담당 — 여기선 상세 단건만)
 */
@Service
public class CardService {

    private static final String PUBLIC = "PUBLIC";

    private final CardRepository cardRepository;
    private final ReportRepository reportRepository;
    private final UserRepository userRepository;
    private final LikeRepository likeRepository;

    public CardService(CardRepository cardRepository, ReportRepository reportRepository,
                       UserRepository userRepository, LikeRepository likeRepository) {
        this.cardRepository = cardRepository;
        this.reportRepository = reportRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
    }

    /**
     * 카드 단건 상세. 카드 상세 화면 진입/새로고침용.
     * 권한 = 내 카드이거나 PUBLIC 카드면 열람(타인 공개 카드·비로그인 게스트 포함).
     * 남의 비공개 카드/없음/형식 오류는 존재 노출 없이 404.
     * reportId(본문 publicId)를 함께 내려 프론트가 본문 조회로 이동할 수 있게 한다.
     *
     * @param viewerId 조회자 id. 비로그인(게스트)이면 null — PUBLIC 카드만 볼 수 있다.
     */
    @Transactional(readOnly = true)
    public CardResponse get(Long viewerId, String publicId) {
        UUID uuid = parseOrNotFound(publicId);
        Card card = cardRepository.findByPublicIdAndDeletedAtIsNull(uuid)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다."));
        boolean mine = viewerId != null && card.getUserId().equals(viewerId);
        if (!mine && !PUBLIC.equals(card.getVisibility())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");
        }
        // 상세 소셜 필드(2026-08-04, 여진 좋아요 UI): 작성자·좋아요 수·내 좋아요 여부.
        // 단건이라 카드당 소량 조회로 충분(배치 불필요). 게스트는 liked=false.
        User author = userRepository.findById(card.getUserId()).orElse(null);
        long likeCount = likeRepository.countByCardId(card.getId());
        boolean liked = viewerId != null && likeRepository.existsByUserIdAndCardId(viewerId, card.getId());
        return CardResponse.forDetail(card, reportPublicId(card), author, likeCount, liked);
    }

    /**
     * 카드 공개설정 변경 (SNS/Week2) — 소유자만 자기 카드의 PUBLIC/PRIVATE 를 바꾼다.
     * 남의 카드는 존재 노출 없이 404. 변경 후 최신 카드 상태를 돌려준다.
     */
    @Transactional
    public CardResponse changeVisibility(Long userId, String publicId, String visibility) {
        UUID uuid = parseOrNotFound(publicId);
        Card card = cardRepository.findByPublicIdAndUserIdAndDeletedAtIsNull(uuid, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다."));
        card.changeVisibility(visibility);   // dirty checking 으로 flush
        return CardResponse.from(card, reportPublicId(card));
    }

    /** 카드가 참조하는 리포트의 publicId(없으면 null). 단건 조회라 1쿼리로 충분. */
    private UUID reportPublicId(Card card) {
        if (card.getReportId() == null) {
            return null;
        }
        return reportRepository.findById(card.getReportId())
                .map(Report::getPublicId)
                .orElse(null);
    }

    /** 잘못된 UUID 형식은 500 대신 404 로 다룬다(존재할 수 없는 카드). */
    private UUID parseOrNotFound(String publicId) {
        try {
            return UUID.fromString(publicId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "카드를 찾을 수 없습니다.");
        }
    }
}
