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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * 랭킹 후보 풀 상한 — 최신순으로 이만큼 읽어 점수로 추린다.
     * 넓힐수록 관심사 매칭이 잘 걸리지만 like/scrap/author 배치 조회 대상도 같이 커진다.
     * 지금 전체 공개 카드가 100건 안팎이라 60이면 사실상 전량을 본다.
     */
    private static final int RANKING_POOL_SIZE = 60;

    /**
     * 동점 카드 자리 바꾸기 주기(초). 5분 — 데모·실사용에서 몇 번 새로고침하는 동안은 순서가 유지되고,
     * 잠시 뒤 다시 보면 다른 카드가 위에 온다. 더 짧으면 연속 새로고침에 화면이 튀고,
     * 더 길면 "계속 고정"으로 느껴진다.
     */
    private static final long ROTATION_SLOT_SECONDS = 300L;

    @Transactional(readOnly = true)
    public List<CardResponse> myFeed(Long userId) {
        List<Card> cards = cardRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
        // 카드→리포트 배치 매핑 (본문 진입점 publicId + 대표 이미지, N+1 회피)
        Map<Long, Report> reports = reportsByReportId(cards);
        return cards.stream()
                // 리포트 없는(즉시) 카드는 reportId=null. 불변맵은 get(null) 에서 NPE 라 null 키 조회를 피한다.
                .map(c -> CardResponse.from(c,
                        c.getReportId() == null ? null : reports.get(c.getReportId())))
                .toList();
    }

    /**
     * 카드들이 참조하는 리포트 id → 리포트 매핑 (1 IN 쿼리). 리포트 없는 카드는 빠진다.
     *
     * <p>예전에는 여기서 publicId 만 뽑아 담았는데, 목록에 대표 이미지를 붙이면서
     * (2026-08-11 우석) 같은 객체의 cover_image 컬럼도 필요해졌다. <b>쿼리는 늘지 않는다</b> —
     * 리포트 엔티티는 이미 이 한 번의 조회로 전부 메모리에 올라와 있었고 값만 버리고 있었다.
     */
    private Map<Long, Report> reportsByReportId(List<Card> cards) {
        List<Long> reportIds = cards.stream()
                .map(Card::getReportId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        return reportRepository.findAllById(reportIds).stream()
                .collect(Collectors.toMap(Report::getId, r -> r));
    }

    /**
     * 공개 피드. followingOnly=true 면 내가 팔로우하는 작성자의 공개 카드만.
     * @param viewerId 조회자 id. 비로그인(게스트)이면 null — liked 는 전부 false.
     *                 팔로잉 스코프는 로그인 전제라 게스트면 401 로 막는다.
     */
    @Transactional(readOnly = true)
    public List<PublicCardResponse> publicFeed(Long viewerId, boolean followingOnly, int limit) {
        int size = clampLimit(limit);
        /*
          랭킹을 하려면 **후보를 화면 개수보다 넓게** 읽어야 한다(2026-08-11 우석).
          최신 20건만 읽어 그 안에서 정렬하면, 조금 오래됐지만 내 관심사에 딱 맞는 카드는
          애초에 후보에 못 들어와 영영 안 보인다. 최신순 상위 N 을 읽어 점수로 추린다.
          팔로잉 스코프는 랭킹을 안 하므로 넓힐 이유가 없다(그대로 화면 개수만).
        */
        int poolSize = followingOnly ? size : Math.min(RANKING_POOL_SIZE, Math.max(size * 3, size));
        Pageable page = PageRequest.of(0, poolSize);

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

        List<PublicCardResponse> responses = cards.stream()
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

        // 팔로잉 스코프는 "그 사람 글을 시간순으로" 보는 화면이라 랭킹을 적용하지 않는다.
        if (followingOnly) {
            return responses;
        }
        // 넓게 읽은 후보를 점수로 정렬한 뒤 화면 개수로 자른다.
        return rankForViewer(responses, viewerId).stream().limit(size).toList();
    }

    /**
     * 공개 피드 랭킹 (2026-08-11 우석 — "매번 같은 목록만 나온다").
     *
     * <p>그동안 서버는 최신순만 내려주고 프론트는 "순서는 서버가 정한다"고 믿어 재정렬을 하지 않아
     * <b>아무도 순위를 매기지 않는 상태</b>였다. 그래서 새 카드가 안 생기면 화면이 그대로였다.
     * 여기서 점수를 매겨 관심사·인기 카드가 위로 오게 한다.
     *
     * <p>점수(높을수록 위):
     * <ul>
     *   <li><b>관심 매칭</b> — topic 일치 +100, category 일치 +40 (정밀 매칭을 확실히 앞세운다)
     *   <li><b>인기</b> — 좋아요 수 × 8, 상한 +40 (인기 카드가 관심사를 이기지는 못하게)
     *   <li><b>신선도</b> — 24시간 이내 +30, 3일 이내 +15 (오래된 인기글이 상단을 점유하지 않게)
     *   <li><b>이미 본 것</b> — 좋아요·스크랩한 카드는 −60 (본 걸 또 위에 놓지 않는다)
     * </ul>
     *
     * <p><b>같은 입력이면 같은 결과다(결정적)</b> — 무작위 셔플은 넣지 않는다. 새로고침마다 순서가
     * 흔들리면 "아까 본 카드가 어디 갔지"가 되기 때문이다. 대신 좋아요·스크랩이라는 <b>행동</b>이
     * 다음 조회의 순서를 바꾼다(읽은 건 내려가고, 그 주제는 matched 로 올라온다).
     * 동점은 최신순으로 갈라 순서가 임의로 흔들리지 않게 한다.
     */
    private List<PublicCardResponse> rankForViewer(List<PublicCardResponse> cards, Long viewerId) {
        if (viewerId == null || cards.size() <= 1) {
            return cards;   // 게스트는 개인화 근거가 없다 → 최신순 그대로
        }
        OffsetDateTime now = OffsetDateTime.now();
        long slot = now.toEpochSecond() / ROTATION_SLOT_SECONDS;
        return cards.stream()
                .sorted(Comparator
                        .comparingInt((PublicCardResponse card) -> -score(card, now))
                        .thenComparingInt(card -> rotationKey(card, slot, viewerId))
                        .thenComparing(PublicCardResponse::createdAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 동점 카드끼리의 자리 바꾸기 (2026-08-11 우석 — "피드가 계속 고정이라 별로다").
     *
     * <p>우리 피드는 새 카드가 자주 생기지 않아서, 점수가 같은 카드들이 항상 같은 순서로 붙어 있으면
     * 화면이 통째로 굳어 보인다. 그래서 <b>같은 점수 구간 안에서만</b> 시간대별로 자리를 바꾼다 —
     * 관심사·인기 순위(점수)는 그대로고, 우열을 가릴 수 없는 카드들의 순서만 돈다.
     *
     * <p><b>난수가 아니라 시간 슬롯 해시다.</b> 같은 슬롯 안에서는 몇 번을 새로고침해도 순서가 같아
     * "방금 본 카드가 사라졌다"가 생기지 않고, 슬롯이 넘어가면 다른 얼굴이 위로 온다.
     * 뷰어 id 를 섞어 사람마다 다른 배치를 보게 한다(모두가 같은 카드만 보는 쏠림 방지).
     */
    private static int rotationKey(PublicCardResponse card, long slot, Long viewerId) {
        int seed = Objects.hash(card.publicId(), slot, viewerId);
        return seed == Integer.MIN_VALUE ? 0 : Math.abs(seed);
    }

    /** 랭킹 점수 — 규칙은 {@link #rankForViewer} 문서 참조. */
    private int score(PublicCardResponse card, OffsetDateTime now) {
        int score = 0;
        if (!card.matchedTopics().isEmpty()) {
            score += 100;
        } else if (!card.matchedCategories().isEmpty()) {
            score += 40;
        }
        score += (int) Math.min(40, card.likeCount() * 8);
        if (card.createdAt() != null) {
            long hours = java.time.Duration.between(card.createdAt(), now).toHours();
            if (hours <= 24) {
                score += 30;
            } else if (hours <= 72) {
                score += 15;
            }
        }
        if (card.liked() || card.scrapped()) {
            score -= 60;
        }
        return score;
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
        // lookup 을 먼저 얻는다 — B안(미연결 관심사 번역)이 이걸 써야 해서, 빈 관심사 판정보다 앞이다.
        TaxonomyLookup lookup = activeTaxonomyLookup();
        if (lookup == null) {
            return Map.of();   // 활성 taxonomy 조회 실패 → 매칭 생략(피드는 정상 제공)
        }
        Set<String> viewerTopics = new HashSet<>(interestRepository.findActiveTopicIds(viewerId));
        // 행동 선호 가중(2026-08-11 확정): 좋아요·북마크한 카드의 topic 도 뷰어 관심으로 간주한다.
        // 신호는 이 2개뿐 — 팔로우는 제외(작성자 관심사가 여러 개라 신호가 모호, 우석 결정).
        // agent 로 보내지 않고 라이브 테이블을 직접 읽는다(송우 결정) → 취소=자동 차감, 소급=자동.
        // category 는 등록 관심사 것만 유지한다 — 행동 신호까지 category 로 넓히면 과대 매칭된다.
        viewerTopics.addAll(cardRepository.findLikedCardTopicIds(viewerId));
        viewerTopics.addAll(cardRepository.findScrappedCardTopicIds(viewerId));
        // B안(2026-08-11, 우석 직접 집행): taxonomy 미연결 관심사(직접 입력·Wiki 추가)를
        // 이름→topic_key 로 번역해 합산 — 이게 없으면 손으로 추가한 관심사는 추천에서 투명인간이다.
        // 완전일치만 3갈래(정식 이름·keywords·이름의 ·/, 조각), 모호 용어 폐기 — agent #43 과 동일
        // 원칙·동일 안전핀. 부분 문자열·유사도는 쓰지 않는다(8/5 이름대조 실패와 다른 부류).
        for (String name : interestRepository.findActiveUnlinkedNames(viewerId)) {
            String topicKey = lookup.termToTopic().get(normalizeTerm(name));
            if (topicKey != null) {
                viewerTopics.add(topicKey);
            }
        }
        Set<String> viewerCategories = new HashSet<>(interestRepository.findActiveCategoryIds(viewerId));
        if (viewerTopics.isEmpty() && viewerCategories.isEmpty()) {
            return Map.of();   // 관심 신호가 하나도 없음 → 매칭 불가(전부 빈 목록)
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
        // B안 번역 사전 — 찾을 말(정식 이름·keywords·이름의 ·/, 조각, 전부 정규화) → topic_key.
        // 한 말이 서로 다른 topic 두 곳에 걸리면 그 말은 버린다(모호하면 안 붙이는 게 낫다 — 우석 안전핀,
        // agent #43 과 동일. 실측: V11 카탈로그에서 충돌은 keywords 1건뿐).
        Map<String, String> termToTopic = new HashMap<>();
        Set<String> ambiguousTerms = new HashSet<>();
        for (InterestTaxonomyResponse.Category category : taxonomy.categories()) {
            categoryNames.put(category.id(), category.name());
            for (InterestTaxonomyResponse.Topic topic : category.topics()) {
                topicToCategory.put(topic.id(), category.id());
                topicNames.put(topic.id(), topic.name());
                registerTerm(termToTopic, ambiguousTerms, topic.name(), topic.id());
                for (String keyword : topic.keywords()) {
                    registerTerm(termToTopic, ambiguousTerms, keyword, topic.id());
                }
                // 이름 조각 — "AI·머신러닝" → "AI"·"머신러닝". 조각이 1개면 정식 이름과 같아 중복이라 생략.
                String[] fragments = topic.name().split("[·,]");
                if (fragments.length > 1) {
                    for (String fragment : fragments) {
                        registerTerm(termToTopic, ambiguousTerms, fragment, topic.id());
                    }
                }
            }
        }
        ambiguousTerms.forEach(termToTopic::remove);
        return new TaxonomyLookup(topicToCategory, topicNames, categoryNames, Map.copyOf(termToTopic));
    }

    /** 번역 사전 등록 — 같은 말이 다른 topic 에 이미 걸려 있으면 모호로 표시(최종 단계에서 제거). */
    private static void registerTerm(Map<String, String> termToTopic, Set<String> ambiguousTerms,
                                     String rawTerm, String topicId) {
        String term = normalizeTerm(rawTerm);
        if (term.isEmpty()) {
            return;
        }
        String existing = termToTopic.putIfAbsent(term, topicId);
        if (existing != null && !existing.equals(topicId)) {
            ambiguousTerms.add(term);
        }
    }

    /** 대조용 정규화 — 앞뒤 공백 제거 + 소문자화(영문 대소문자 차이 흡수, agent #43 casefold 와 짝). */
    private static String normalizeTerm(String value) {
        return value == null ? "" : value.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private record TaxonomyLookup(Map<String, String> topicToCategory,
                                  Map<String, String> topicNames,
                                  Map<String, String> categoryNames,
                                  Map<String, String> termToTopic) {
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
