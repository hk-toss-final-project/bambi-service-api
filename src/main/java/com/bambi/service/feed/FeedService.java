package com.bambi.service.feed;

import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.card.dto.CardResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.feed.dto.PublicCardResponse;
import com.bambi.service.follow.FollowRepository;
import com.bambi.service.interest.InterestRepository;
import com.bambi.service.interest.taxonomy.InterestTaxonomyService;
import com.bambi.service.interest.taxonomy.dto.InterestTaxonomyResponse;
import com.bambi.service.like.LikeRepository;
import com.bambi.service.report.Report;
import com.bambi.service.report.ReportRepository;
import com.bambi.service.scrap.ScrapRepository;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 카드 피드.
 * - 내 피드(P0): 내 카드 전부 최신순.
 * - 공개 피드(SNS/Week2): PUBLIC 카드 최신순 + 작성자/좋아요 수/내 좋아요 여부.
 *   좋아요 수·내 좋아요·작성자는 카드별 재조회 없이 각각 1번의 배치 쿼리로 합친다(N+1 차단).
 */
@Service
public class FeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final CardRepository cardRepository;
    private final LikeRepository likeRepository;
    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final ScrapRepository scrapRepository;
    private final InterestRepository interestRepository;
    private final InterestTaxonomyService taxonomyService;

    public FeedService(CardRepository cardRepository,
                       LikeRepository likeRepository,
                       FollowRepository followRepository,
                       UserRepository userRepository,
                       ReportRepository reportRepository,
                       ScrapRepository scrapRepository,
                       InterestRepository interestRepository,
                       InterestTaxonomyService taxonomyService) {
        this.cardRepository = cardRepository;
        this.likeRepository = likeRepository;
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.reportRepository = reportRepository;
        this.scrapRepository = scrapRepository;
        this.interestRepository = interestRepository;
        this.taxonomyService = taxonomyService;
    }

    /** 추천 매칭 결과 홀더(카드별). 없으면 {@link #EMPTY_MATCHED}. */
    private record Matched(List<PublicCardResponse.MatchedTopic> topics,
                           List<PublicCardResponse.MatchedCategory> categories) {
    }

    private static final Matched EMPTY_MATCHED = new Matched(List.of(), List.of());

    @Transactional(readOnly = true)
    public List<CardResponse> myFeed(Long userId) {
        List<Card> cards = cardRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
        // 카드→리포트 publicId 배치 매핑 (프론트가 본문으로 이동할 진입점, N+1 회피)
        Map<Long, UUID> reportPublicIds = reportPublicIdsByReportId(cards);
        return cards.stream()
                // 리포트 없는(즉시) 카드는 reportId=null. 불변맵은 get(null) 에서 NPE 라 null 키 조회를 피한다.
                .map(c -> CardResponse.from(c,
                        c.getReportId() == null ? null : reportPublicIds.get(c.getReportId())))
                .toList();
    }

    /** 카드들이 참조하는 리포트 id → publicId 매핑 (1 IN 쿼리). 리포트 없는 카드는 빠진다. */
    private Map<Long, UUID> reportPublicIdsByReportId(List<Card> cards) {
        List<Long> reportIds = cards.stream()
                .map(Card::getReportId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        return reportRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Report::getId, Report::getPublicId));
    }

    /**
     * 공개 피드. followingOnly=true 면 내가 팔로우하는 작성자의 공개 카드만.
     * @param viewerId 조회자 id. 비로그인(게스트)이면 null — liked 는 전부 false.
     *                 팔로잉 스코프는 로그인 전제라 게스트면 401 로 막는다.
     */
    @Transactional(readOnly = true)
    public List<PublicCardResponse> publicFeed(Long viewerId, boolean followingOnly, int limit) {
        Pageable page = PageRequest.of(0, clampLimit(limit));

        List<Card> cards;
        if (followingOnly) {
            if (viewerId == null) {
                throw new ApiException(ErrorCode.AUTH_INVALID_TOKEN, "팔로잉 피드는 로그인이 필요합니다.");
            }
            List<Long> authorIds = followRepository.findFolloweeIds(viewerId);
            if (authorIds.isEmpty()) {
                return List.of();   // 아무도 팔로우 안 함 → 빈 피드(프론트 Empty State)
            }
            cards = cardRepository.findPublicFeedByAuthors(authorIds, page);
        } else {
            cards = cardRepository.findPublicFeed(page);
        }
        if (cards.isEmpty()) {
            return List.of();
        }

        List<Long> cardIds = cards.stream().map(Card::getId).toList();

        // ① 카드별 좋아요 수 (1 group-by 쿼리)
        Map<Long, Long> likeCounts = likeRepository.countByCardIds(cardIds).stream()
                .collect(Collectors.toMap(LikeRepository.CardLikeCount::getCardId,
                        LikeRepository.CardLikeCount::getCount));
        // ② 내가 좋아요한 카드 집합 (1 IN 쿼리) — 게스트(viewerId=null)는 조회 없이 전부 false
        Set<Long> likedIds = likedCardIds(viewerId, cardIds);
        // ③ 내가 스크랩한 카드 집합 (1 IN 쿼리) — 게스트는 조회 없이 전부 false
        Set<Long> scrappedIds = scrappedCardIds(viewerId, cardIds);
        // ④ 작성자 정보 (1 IN 쿼리)
        // TODO(follow-up 이슈): 탈퇴(soft delete) 작성자의 PUBLIC 카드가 피드에 남는다.
        //   FK CASCADE 는 하드 삭제에만 반응하므로, "작성자 생존(deleted_at IS NULL) 필터"를
        //   피드 쿼리/서비스에 추가해야 한다. 이번 PR 범위 밖 — 별도 이슈로 처리.
        List<Long> authorIds = cards.stream().map(Card::getUserId).distinct().toList();
        Map<Long, User> authors = userRepository.findAllById(authorIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        // ⑤ 추천 매칭 (뷰어 관심 topic/category ∩ 카드 topic) — 뷰어 관심/taxonomy 각 1회 조회, 카드별 재조회 없음
        Map<Long, Matched> matched = matchedByCard(viewerId, cards);

        return cards.stream()
                .map(card -> {
                    Matched m = matched.getOrDefault(card.getId(), EMPTY_MATCHED);
                    return PublicCardResponse.from(
                            card,
                            authors.get(card.getUserId()),
                            likeCounts.getOrDefault(card.getId(), 0L),
                            likedIds.contains(card.getId()),
                            scrappedIds.contains(card.getId()),
                            m.topics(),
                            m.categories());
                })
                .toList();
    }

    /**
     * 추천 매칭(계약 A안, 2단): 뷰어 관심 topic/category 와 카드 taxonomy topic 을 교집합해 카드별
     * {matchedTopics(정밀), matchedCategories(넓음)} 를 만든다. topic 44개 exact 만으론 비는 경우가 많아
     * category(8개)를 recall 안전망으로 함께 낸다.
     * 게스트(null)·관심사 미설정·활성 taxonomy 없음이면 빈 맵(전부 빈 목록). 뷰어 관심/taxonomy 는 각 1회만 조회.
     */
    private Map<Long, Matched> matchedByCard(Long viewerId, List<Card> cards) {
        if (viewerId == null) {
            return Map.of();   // 게스트 → 매칭 없음
        }
        Set<String> viewerTopics = new HashSet<>(interestRepository.findActiveTopicIds(viewerId));
        Set<String> viewerCategories = new HashSet<>(interestRepository.findActiveCategoryIds(viewerId));
        if (viewerTopics.isEmpty() && viewerCategories.isEmpty()) {
            return Map.of();   // taxonomy 연결 관심사 없음 → 매칭 불가(전부 빈 목록)
        }
        TaxonomyLookup lookup = activeTaxonomyLookup();
        if (lookup == null) {
            return Map.of();   // 활성 taxonomy 조회 실패 → 매칭 생략(피드는 정상 제공)
        }

        Map<Long, Matched> result = new HashMap<>();
        for (Card card : cards) {
            Set<String> cardTopics = card.getTaxonomyTopicIds();
            if (cardTopics == null || cardTopics.isEmpty()) {
                continue;   // 롤아웃 전/미매핑 카드 → 기본(빈 목록)
            }
            List<PublicCardResponse.MatchedTopic> topics = new ArrayList<>();
            Set<String> cardCategories = new LinkedHashSet<>();
            for (String topicKey : cardTopics) {
                String categoryKey = lookup.topicToCategory().get(topicKey);
                if (categoryKey == null) {
                    continue;   // 카드 topic 이 활성 taxonomy 에 없음(버전 드리프트) → 스킵
                }
                cardCategories.add(categoryKey);
                if (viewerTopics.contains(topicKey)) {
                    topics.add(new PublicCardResponse.MatchedTopic(
                            topicKey, lookup.topicNames().get(topicKey)));
                }
            }
            List<PublicCardResponse.MatchedCategory> categories = new ArrayList<>();
            for (String categoryKey : cardCategories) {
                if (viewerCategories.contains(categoryKey)) {
                    categories.add(new PublicCardResponse.MatchedCategory(
                            categoryKey, lookup.categoryNames().get(categoryKey)));
                }
            }
            if (!topics.isEmpty() || !categories.isEmpty()) {
                result.put(card.getId(), new Matched(topics, categories));
            }
        }
        return result;
    }

    /** 활성 taxonomy 를 매칭 조회 맵(topic→category / topic→name / category→name)으로. 없거나 오류면 null. */
    private TaxonomyLookup activeTaxonomyLookup() {
        InterestTaxonomyResponse taxonomy;
        try {
            taxonomy = taxonomyService.getActiveTaxonomy();
        } catch (RuntimeException e) {
            return null;
        }
        Map<String, String> topicToCategory = new HashMap<>();
        Map<String, String> topicNames = new HashMap<>();
        Map<String, String> categoryNames = new HashMap<>();
        for (InterestTaxonomyResponse.Category category : taxonomy.categories()) {
            categoryNames.put(category.id(), category.name());
            for (InterestTaxonomyResponse.Topic topic : category.topics()) {
                topicToCategory.put(topic.id(), category.id());
                topicNames.put(topic.id(), topic.name());
            }
        }
        return new TaxonomyLookup(topicToCategory, topicNames, categoryNames);
    }

    private record TaxonomyLookup(Map<String, String> topicToCategory,
                                  Map<String, String> topicNames,
                                  Map<String, String> categoryNames) {
    }

    /** 게스트(viewerId=null)는 조회 없이 빈 집합 — liked/scrapped 는 전부 false 가 된다. */
    private Set<Long> likedCardIds(Long viewerId, List<Long> cardIds) {
        return viewerId == null ? Set.of()
                : new HashSet<>(likeRepository.findLikedCardIds(viewerId, cardIds));
    }

    private Set<Long> scrappedCardIds(Long viewerId, List<Long> cardIds) {
        return viewerId == null ? Set.of()
                : new HashSet<>(scrapRepository.findScrappedCardIds(viewerId, cardIds));
    }

    /**
     * 작성자별 공개 카드(프로필 화면 몸통, 07-31). PUBLIC 카드만 최신순 —
     * 본인이 자기 프로필을 봐도 비공개 카드는 여기 안 나온다(그건 내 피드 몫).
     * @param viewerId 조회자 id. 비로그인(게스트)이면 null — liked 는 전부 false.
     */
    @Transactional(readOnly = true)
    public List<PublicCardResponse> publicCardsByAuthor(Long viewerId, String authorPublicId, int limit) {
        User author = resolveAuthor(authorPublicId);
        List<Card> cards = cardRepository.findPublicFeedByAuthors(
                List.of(author.getId()), PageRequest.of(0, clampLimit(limit)));
        if (cards.isEmpty()) {
            return List.of();
        }

        List<Long> cardIds = cards.stream().map(Card::getId).toList();
        Map<Long, Long> likeCounts = likeRepository.countByCardIds(cardIds).stream()
                .collect(Collectors.toMap(LikeRepository.CardLikeCount::getCardId,
                        LikeRepository.CardLikeCount::getCount));
        Set<Long> likedIds = likedCardIds(viewerId, cardIds);
        Set<Long> scrappedIds = scrappedCardIds(viewerId, cardIds);

        // 추천 매칭은 공개 피드(추천 레일) 전용 — 프로필 카드 목록은 매칭 미부여(빈 목록)로 스코프 유지.
        return cards.stream()
                .map(card -> PublicCardResponse.from(
                        card,
                        author,
                        likeCounts.getOrDefault(card.getId(), 0L),
                        likedIds.contains(card.getId()),
                        scrappedIds.contains(card.getId()),
                        List.of(),
                        List.of()))
                .toList();
    }

    /** publicId 로 살아있는 작성자 조회. 형식 오류/없음은 존재 노출 없이 404(프로필과 동일 정책). */
    private User resolveAuthor(String publicId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(publicId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return userRepository.findByPublicIdAndDeletedAtIsNull(uuid)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
