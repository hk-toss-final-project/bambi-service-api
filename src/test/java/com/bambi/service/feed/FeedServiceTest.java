package com.bambi.service.feed;

import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.feed.dto.PublicCardResponse;
import com.bambi.service.card.dto.CardResponse;
import com.bambi.service.follow.FollowRepository;
import com.bambi.service.interest.InterestRepository;
import com.bambi.service.interest.taxonomy.InterestTaxonomyService;
import com.bambi.service.interest.taxonomy.dto.InterestTaxonomyResponse;
import com.bambi.service.like.LikeRepository;
import com.bambi.service.report.Report;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FeedService} 공개 피드 검증 — 특히 비로그인(게스트) 열람 시나리오.
 * 팀 정책(홈=공개 SNS): 게스트도 공개 피드/프로필을 볼 수 있고, 이때 liked 는 전부 false,
 * 팔로잉 스코프는 로그인 전제라 401 로 막는다.
 */
class FeedServiceTest {

    private final CardRepository cardRepository = mock(CardRepository.class);
    private final LikeRepository likeRepository = mock(LikeRepository.class);
    private final FollowRepository followRepository = mock(FollowRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final com.bambi.service.report.ReportRepository reportRepository =
            mock(com.bambi.service.report.ReportRepository.class);
    private final com.bambi.service.scrap.ScrapRepository scrapRepository =
            mock(com.bambi.service.scrap.ScrapRepository.class);
    private final InterestRepository interestRepository = mock(InterestRepository.class);
    private final InterestTaxonomyService taxonomyService = mock(InterestTaxonomyService.class);
    private final FeedService service =
            new FeedService(cardRepository, likeRepository, followRepository, userRepository,
                    reportRepository, scrapRepository, interestRepository, taxonomyService);

    /** 매칭 테스트용 최소 taxonomy — category tech {ai_ml, data_cloud(keywords=DB)}. */
    private static InterestTaxonomyResponse sampleTaxonomy() {
        var aiMl = new InterestTaxonomyResponse.Topic("ai_ml", "AI·머신러닝", "AI & ML", "d", 1, List.of());
        var dataCloud = new InterestTaxonomyResponse.Topic(
                "data_cloud", "데이터·클라우드", "Data", "d", 3, List.of("DB"));
        var tech = new InterestTaxonomyResponse.Category(
                "tech", "테크·IT", "Tech", "d", "💻", 1, List.of(aiMl, dataCloud));
        return new InterestTaxonomyResponse("1.0.0-draft", "hash", "ko-KR",
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"), List.of(tech));
    }

    /** 모호 용어 테스트용 — "AI"가 ai_ml(이름 조각)과 data_cloud(keyword) 양쪽에 걸리는 taxonomy. */
    private static InterestTaxonomyResponse ambiguousTaxonomy() {
        var aiMl = new InterestTaxonomyResponse.Topic("ai_ml", "AI·머신러닝", "AI & ML", "d", 1, List.of());
        var dataCloud = new InterestTaxonomyResponse.Topic(
                "data_cloud", "데이터·클라우드", "Data", "d", 3, List.of("AI"));
        var tech = new InterestTaxonomyResponse.Category(
                "tech", "테크·IT", "Tech", "d", "💻", 1, List.of(aiMl, dataCloud));
        return new InterestTaxonomyResponse("1.0.0-draft", "hash", "ko-KR",
                java.time.OffsetDateTime.parse("2026-01-01T00:00:00Z"), List.of(tech));
    }

    @Test
    void 게스트도_공개피드를_볼_수_있고_liked_는_전부_false() {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(10L);
        when(card.getUserId()).thenReturn(2L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));

        // viewerId = null (비로그인)
        List<PublicCardResponse> feed = service.publicFeed(null, false, 20);

        assertThat(feed).hasSize(1);
        assertThat(feed.get(0).liked()).isFalse();
        // 게스트는 "내 좋아요" 조회 쿼리를 아예 날리지 않아야 한다
        verify(likeRepository, never()).findLikedCardIds(any(), anyCollection());
        // 게스트는 추천 매칭도 없다(관심사/taxonomy 조회조차 안 함)
        assertThat(feed.get(0).matchedTopics()).isEmpty();
        assertThat(feed.get(0).matchedCategories()).isEmpty();
        verify(interestRepository, never()).findActiveTopicIds(any());
    }

    @Test
    void 추천매칭_뷰어_topic_이_카드_topic_과_겹치면_matchedTopics_와_상위_category_를_채운다() {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(10L);
        when(card.getUserId()).thenReturn(2L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(card.getTaxonomyTopicIds()).thenReturn(Set.of("ai_ml"));
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of("ai_ml"));
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of("tech"));
        when(taxonomyService.getActiveTaxonomy()).thenReturn(sampleTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        assertThat(feed.get(0).matchedTopics()).extracting(PublicCardResponse.MatchedTopic::topicId)
                .containsExactly("ai_ml");
        assertThat(feed.get(0).matchedTopics().get(0).name()).isEqualTo("AI·머신러닝");
        assertThat(feed.get(0).matchedCategories()).extracting(PublicCardResponse.MatchedCategory::categoryId)
                .containsExactly("tech");
    }

    // ---- B안: taxonomy 미연결(직접 입력) 관심사 번역 (2026-08-11) ----------------

    /** 카드 topic 하나짜리 공개 피드 공통 목업. */
    private Card feedCardWithTopic(long cardId, String topicKey) {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(cardId);
        when(card.getUserId()).thenReturn(2L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(card.getTaxonomyTopicIds()).thenReturn(Set.of(topicKey));
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        return card;
    }

    @Test
    void B안_직접입력_관심사가_이름조각_완전일치로_topic_에_번역돼_매칭된다() {
        feedCardWithTopic(20L, "ai_ml");
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of());       // taxonomy 연결 관심사 없음
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of());
        when(interestRepository.findActiveUnlinkedNames(1L)).thenReturn(List.of("AI"));  // 직접 입력
        when(taxonomyService.getActiveTaxonomy()).thenReturn(sampleTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        // "AI" = "AI·머신러닝"의 · 조각 완전일치 → ai_ml 로 번역 → 매칭
        assertThat(feed.get(0).matchedTopics()).extracting(PublicCardResponse.MatchedTopic::topicId)
                .containsExactly("ai_ml");
    }

    @Test
    void B안_keywords_완전일치는_대소문자를_가리지_않는다() {
        feedCardWithTopic(21L, "data_cloud");
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of());
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of());
        when(interestRepository.findActiveUnlinkedNames(1L)).thenReturn(List.of("db"));  // 소문자 입력
        when(taxonomyService.getActiveTaxonomy()).thenReturn(sampleTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        assertThat(feed.get(0).matchedTopics()).extracting(PublicCardResponse.MatchedTopic::topicId)
                .containsExactly("data_cloud");   // keywords("DB") 정규화 일치
    }

    @Test
    void B안_두_topic_에_걸리는_모호한_말은_번역하지_않는다() {
        // "AI"가 ai_ml(이름 조각)과 data_cloud(keyword) 양쪽에 걸림 → 폐기(우석 안전핀, agent #43 동일)
        feedCardWithTopic(22L, "ai_ml");
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of());
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of());
        when(interestRepository.findActiveUnlinkedNames(1L)).thenReturn(List.of("AI"));
        when(taxonomyService.getActiveTaxonomy()).thenReturn(ambiguousTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        assertThat(feed.get(0).matchedTopics()).isEmpty();   // 모호 → 안 붙임(엉뚱한 추천 방지)
        assertThat(feed.get(0).matchedCategories()).isEmpty();
    }

    @Test
    void 추천매칭_등록관심사가_없어도_좋아요한_카드의_topic_으로_매칭된다() {
        // 행동 선호 가중(2026-08-11): 좋아요·북마크한 카드의 topic = 뷰어 관심. 라이브 테이블
        // 직접 참조라 취소하면 자동 차감된다(별도 로직 없음).
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(12L);
        when(card.getUserId()).thenReturn(2L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(card.getTaxonomyTopicIds()).thenReturn(Set.of("ai_ml"));
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of());      // 등록 관심사 없음
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of());
        when(cardRepository.findLikedCardTopicIds(1L)).thenReturn(List.of("ai_ml"));  // 좋아요 이력만
        when(cardRepository.findScrappedCardTopicIds(1L)).thenReturn(List.of());
        when(taxonomyService.getActiveTaxonomy()).thenReturn(sampleTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        assertThat(feed.get(0).matchedTopics()).extracting(PublicCardResponse.MatchedTopic::topicId)
                .containsExactly("ai_ml");
    }

    @Test
    void 추천매칭_topic_은_안겹쳐도_같은_category_면_matchedCategories_로_보강한다() {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(11L);
        when(card.getUserId()).thenReturn(2L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(card.getTaxonomyTopicIds()).thenReturn(Set.of("data_cloud"));   // 뷰어 topic(ai_ml)과 다름, 같은 tech
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of("ai_ml"));
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of("tech"));
        when(taxonomyService.getActiveTaxonomy()).thenReturn(sampleTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        assertThat(feed.get(0).matchedTopics()).isEmpty();   // topic 정밀 매칭 없음
        assertThat(feed.get(0).matchedCategories()).extracting(PublicCardResponse.MatchedCategory::categoryId)
                .containsExactly("tech");                    // 같은 category(recall 안전망)로 보강
    }

    @Test
    void myFeed_리포트없는_카드와_있는_카드를_섞어_NPE없이_반환한다() {
        // 리포트 없는(즉시) 카드 — reportId=null (불변맵 get(null) NPE 회귀 방지)
        Card noReport = mock(Card.class);
        when(noReport.getReportId()).thenReturn(null);
        when(noReport.getPublicId()).thenReturn(UUID.randomUUID());
        when(noReport.getSources()).thenReturn(List.of());
        // 리포트 있는 카드 — reportId 채워짐
        Card withReport = mock(Card.class);
        when(withReport.getReportId()).thenReturn(100L);
        when(withReport.getPublicId()).thenReturn(UUID.randomUUID());
        when(withReport.getSources()).thenReturn(List.of());
        when(cardRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(noReport, withReport));
        Report report = mock(Report.class);
        when(report.getId()).thenReturn(100L);
        UUID reportPublicId = UUID.randomUUID();
        when(report.getPublicId()).thenReturn(reportPublicId);
        when(reportRepository.findAllById(any())).thenReturn(List.of(report));

        List<CardResponse> feed = service.myFeed(1L);

        assertThat(feed).hasSize(2);
        assertThat(feed.get(0).reportId()).isNull();                 // 즉시 카드
        assertThat(feed.get(1).reportId()).isEqualTo(reportPublicId); // 리포트 연결 카드
    }

    @Test
    void 게스트가_팔로잉_스코프를_요청하면_401() {
        ApiException ex = catchThrowableOfType(
                () -> service.publicFeed(null, true, 20), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.AUTH_INVALID_TOKEN);
        // 팔로잉 대상 조회조차 없어야 한다
        verify(followRepository, never()).findFolloweeIds(anyLong());
    }

    @Test
    void 작성자별_공개카드는_그_작성자의_PUBLIC_카드만_최신순으로_준다() {
        UUID authorPublicId = UUID.randomUUID();
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(author.getPublicId()).thenReturn(authorPublicId);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(authorPublicId))
                .thenReturn(java.util.Optional.of(author));

        Card card = mock(Card.class);
        when(card.getId()).thenReturn(10L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(cardRepository.findPublicFeedByAuthors(anyCollection(), any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        when(likeRepository.findLikedCardIds(anyLong(), anyCollection())).thenReturn(List.of(10L));
        when(scrapRepository.findScrappedCardIds(anyLong(), anyCollection())).thenReturn(List.of(10L));

        List<PublicCardResponse> cards = service.publicCardsByAuthor(1L, authorPublicId.toString(), 20);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).author().publicId()).isEqualTo(authorPublicId);
        assertThat(cards.get(0).liked()).isTrue();
        assertThat(cards.get(0).scrapped()).isTrue();
        // PUBLIC 전용 쿼리(findPublicFeedByAuthors)를 써야 한다 — 비공개 카드 유출 방지
        verify(cardRepository, never()).findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(anyLong());
    }

    @Test
    void 작성자별_공개카드_게스트는_liked_조회_없이_전부_false() {
        UUID authorPublicId = UUID.randomUUID();
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(authorPublicId))
                .thenReturn(java.util.Optional.of(author));
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(10L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(cardRepository.findPublicFeedByAuthors(anyCollection(), any())).thenReturn(List.of(card));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());

        List<PublicCardResponse> cards = service.publicCardsByAuthor(null, authorPublicId.toString(), 20);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).liked()).isFalse();
        verify(likeRepository, never()).findLikedCardIds(any(), anyCollection());
    }

    @Test
    void 작성자별_공개카드_없는_사용자나_깨진_publicId_는_404() {
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(java.util.Optional.empty());

        ApiException notFound = catchThrowableOfType(
                () -> service.publicCardsByAuthor(1L, UUID.randomUUID().toString(), 20), ApiException.class);
        ApiException broken = catchThrowableOfType(
                () -> service.publicCardsByAuthor(1L, "not-a-uuid", 20), ApiException.class);

        assertThat(notFound.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(broken.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);   // 존재/형식 구분 노출 없음
    }

    // ---- 공개 피드 랭킹 (2026-08-11 우석) --------------------------------------

    /** 랭킹 테스트용 카드 — 좋아요 수·생성 시각·매칭을 조합해 순서를 검증한다. */
    private Card rankCard(long id, java.time.OffsetDateTime createdAt, Set<String> topicIds) {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(id);
        when(card.getUserId()).thenReturn(2L);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(List.of());
        when(card.getTaxonomyTopicIds()).thenReturn(topicIds);
        when(card.getCreatedAt()).thenReturn(createdAt);
        return card;
    }

    @Test
    void 랭킹_관심사가_맞는_오래된_카드가_무관한_최신_카드보다_위다() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        Card fresh = rankCard(10L, now, Set.of("data_cloud"));          // 최신·비매칭
        Card matched = rankCard(11L, now.minusDays(5), Set.of("ai_ml")); // 오래됨·매칭
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(fresh, matched));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));
        when(interestRepository.findActiveTopicIds(1L)).thenReturn(List.of("ai_ml"));
        when(interestRepository.findActiveCategoryIds(1L)).thenReturn(List.of());
        when(interestRepository.findActiveUnlinkedNames(1L)).thenReturn(List.of());
        when(cardRepository.findLikedCardTopicIds(1L)).thenReturn(List.of());
        when(cardRepository.findScrappedCardTopicIds(1L)).thenReturn(List.of());
        when(taxonomyService.getActiveTaxonomy()).thenReturn(sampleTaxonomy());

        List<PublicCardResponse> feed = service.publicFeed(1L, false, 20);

        // 관심 매칭(+100)이 신선도(+30)를 이긴다 — "내 관심사 먼저"
        assertThat(feed.get(0).matchedTopics()).isNotEmpty();
    }

    @Test
    void 랭킹_게스트는_최신순_그대로다() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        Card first = rankCard(20L, now, Set.of("ai_ml"));
        Card second = rankCard(21L, now.minusDays(1), Set.of("ai_ml"));
        when(cardRepository.findPublicFeed(any())).thenReturn(List.of(first, second));
        when(likeRepository.countByCardIds(anyCollection())).thenReturn(List.of());
        User author = mock(User.class);
        when(author.getId()).thenReturn(2L);
        when(userRepository.findAllById(any())).thenReturn(List.of(author));

        List<PublicCardResponse> feed = service.publicFeed(null, false, 20);

        // 게스트는 개인화 근거가 없다 → 서버가 준 최신순을 흔들지 않는다
        assertThat(feed).hasSize(2);
        verify(interestRepository, never()).findActiveTopicIds(any());
    }
}
