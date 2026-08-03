package com.bambi.service.bookmark;

import com.bambi.service.agent.AgentClient;
import com.bambi.service.agent.dto.BookmarkProcessRequest;
import com.bambi.service.agent.dto.BookmarkProcessResponse;
import com.bambi.service.agent.dto.CardGenerateRequest;
import com.bambi.service.agent.dto.CardGenerateResponse;
import com.bambi.service.agent.publish.MockPublishInbox;
import com.bambi.service.bookmark.dto.BookmarkCreateRequest;
import com.bambi.service.bookmark.dto.BookmarkCreateResponse;
import com.bambi.service.bookmark.dto.BookmarkDetailResponse;
import com.bambi.service.bookmark.dto.BookmarkResponse;
import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.card.dto.CardResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 저장→카드 세로 슬라이스의 몸통.
 * P0 합의안 "즉시 카드 1장": 저장 API 안에서 동기로 Mock Agent 를 호출해
 * 요약/카드까지 한 트랜잭션으로 저장한다 (전부 성공 or 전부 롤백 — 원자성).
 * 주의: P1 에서 Agent 가 실제 HTTP 호출로 바뀌면 외부 호출을 트랜잭션 밖으로
 * 빼는 구조(Kafka 비동기 or 상태 전이 분리)로 재설계한다.
 */
@Service
public class BookmarkService {

    private static final Logger log = LoggerFactory.getLogger(BookmarkService.class);

    private final BookmarkRepository bookmarkRepository;
    private final CardRepository cardRepository;
    private final AgentClient agentClient;
    // 발행 큐 트리거. mock 모드에서만 존재한다(인프로세스 큐). http 모드면 비어 있고,
    // 재처리는 실제 agent POST /generations 트리거로 가야 한다(P1, 영현 스케줄러). ObjectProvider 로 선택 주입.
    private final ObjectProvider<MockPublishInbox> publishInbox;
    private final ApplicationEventPublisher eventPublisher;
    // 저장 시 동기 Mock 즉시 카드 — P0 관통용 안전판. 제품 모델(저장≠생성) 기준으론 꺼야 하며,
    // 비동기 발행 경로가 검증된 환경에서 AGENT_IMMEDIATE_CARD_ENABLED=false 로 끈다. 코드 제거 금지(루트 CLAUDE.md).
    private final boolean immediateCardEnabled;

    public BookmarkService(BookmarkRepository bookmarkRepository,
                           CardRepository cardRepository,
                           AgentClient agentClient,
                           ObjectProvider<MockPublishInbox> publishInbox,
                           ApplicationEventPublisher eventPublisher,
                           @Value("${app.agent.immediate-card.enabled:true}") boolean immediateCardEnabled) {
        this.bookmarkRepository = bookmarkRepository;
        this.cardRepository = cardRepository;
        this.agentClient = agentClient;
        this.publishInbox = publishInbox;
        this.eventPublisher = eventPublisher;
        this.immediateCardEnabled = immediateCardEnabled;
    }

    @Transactional
    public BookmarkCreateResponse create(Long userId, BookmarkCreateRequest req) {
        // url 또는 content 중 하나는 있어야 저장할 원본이 된다
        if (!req.hasUrl() && !req.hasContent()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "url 또는 content 중 하나는 필수입니다.");
        }
        // 같은 유저의 살아있는 동일 URL 재저장 방지 (DB 유니크 인덱스가 최종 방어선)
        if (req.hasUrl() && bookmarkRepository.existsByUserIdAndUrlAndDeletedAtIsNull(userId, req.url())) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "이미 저장한 URL입니다.");
        }

        Bookmark bookmark = new Bookmark(userId, req.hasUrl() ? req.url() : null, req.title(), req.content());
        bookmarkRepository.save(bookmark);   // PROCESSING 으로 저장, id 확보

        // 즉시카드 OFF — 저장은 여기서 끝. 요약·주제는 agent 위키 파이프라인(클리핑 중계)이,
        // 카드는 비동기 발행 경로(생성 트리거→claim→report+card)가 담당한다. card 는 null 로 내려간다
        // (프론트는 onSaved 에서 card 를 쓰지 않고 refetch 만 한다 — 2026-08-03 실측).
        if (!immediateCardEnabled) {
            bookmark.completeProcessing(null);
            eventPublisher.publishEvent(new BookmarkSavedEvent(
                    userId, bookmark.getId(), bookmark.getUrl(), bookmark.getTitle(), bookmark.getContent()));
            return new BookmarkCreateResponse(BookmarkResponse.from(bookmark), null);
        }

        // 1) 요약/관심사 추론 (계약: /agent/bookmarks/process)
        BookmarkProcessResponse processed = agentClient.processBookmark(new BookmarkProcessRequest(
                bookmark.getId(), bookmark.getTitle(), bookmark.getUrl(), bookmark.getContent()));

        // 2) 카드 생성 (계약: /agent/cards/generate) — 재료는 방금 저장한 북마크 1건
        CardGenerateResponse generated = agentClient.generateCards(new CardGenerateRequest(
                userId,
                processed.interests(),
                List.of(new CardGenerateRequest.BookmarkPayload(
                        bookmark.getTitle(), processed.summary(), bookmark.getUrl())),
                List.of()));   // collectedItems 는 RSS 수집(P1) 전까지 빈 배열

        // 3) 사용자 노출 데이터 저장은 Service 책임 — 카드(+출처) 저장 후 북마크 DONE
        Card card = saveFirstCard(userId, bookmark, generated);
        bookmark.completeProcessing(processed.summary());

        // 저장 커밋 후 agent 위키 원천 처리로 중계한다(AFTER_COMMIT 리스너). 중계 실패는 저장을 막지 않는다.
        eventPublisher.publishEvent(new BookmarkSavedEvent(
                userId, bookmark.getId(), bookmark.getUrl(), bookmark.getTitle(), bookmark.getContent()));

        return new BookmarkCreateResponse(BookmarkResponse.from(bookmark), CardResponse.from(card));
    }

    @Transactional(readOnly = true)
    public List<BookmarkResponse> list(Long userId) {
        return bookmarkRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(BookmarkResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public BookmarkDetailResponse get(Long userId, Long bookmarkId) {
        Bookmark bookmark = bookmarkRepository.findByIdAndUserIdAndDeletedAtIsNull(bookmarkId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "북마크를 찾을 수 없습니다."));
        return BookmarkDetailResponse.from(bookmark);
    }

    /**
     * 비동기 재처리 요청 — agent 의 심화 발행(Pull)을 흉내낸다.
     * 동기 즉시 카드와 별개로, 발행 큐에 항목을 넣으면 service-worker 가 폴링으로 집어
     * 잠시 뒤 심화 카드를 발행한다("처리중 → 도착"). 계약상 202 Accepted 성격.
     * content_id 는 bookmark 기준 결정적 값이라 같은 북마크 재요청은 멱등(카드 1장).
     */
    @Transactional(readOnly = true)
    public void requestReprocess(Long userId, Long bookmarkId) {
        Bookmark bookmark = bookmarkRepository.findByIdAndUserIdAndDeletedAtIsNull(bookmarkId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "북마크를 찾을 수 없습니다."));
        String contentId = "bookmark-" + bookmark.getId();
        MockPublishInbox inbox = publishInbox.getIfAvailable();
        if (inbox == null) {
            // http 모드 — 인프로세스 큐가 없다. 실제 agent 생성 트리거(POST /generations)는
            // 생성 스케줄러(P1, 영현)가 담당하므로 여기선 무시한다. TODO: 스케줄러 배선되면 연결.
            log.debug("[Bookmark] http 모드 재처리 요청 무시(contentId={}) — 실제 발행은 agent 생성 트리거 경유", contentId);
            return;
        }
        inbox.enqueue(
                userId,
                contentId,
                (bookmark.getTitle() != null ? bookmark.getTitle() : "저장한 콘텐츠") + " — 심화 브리핑",
                bookmark.getTitle(),
                bookmark.getUrl());
    }

    /** Agent 응답의 첫 카드를 저장한다. 카드가 없거나 출처가 비면 저장하지 않는다(출처 없는 카드 금지). */
    private Card saveFirstCard(Long userId, Bookmark bookmark, CardGenerateResponse generated) {
        if (generated.cards() == null || generated.cards().isEmpty()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Agent가 카드를 생성하지 못했습니다.");
        }
        CardGenerateResponse.GeneratedCard first = generated.cards().get(0);
        Card card = new Card(userId, first.title(), first.summary(), first.whyForYou());
        if (first.sources() == null || first.sources().isEmpty()) {
            // 출처가 전혀 없으면 최소한 원본 북마크를 출처로 남긴다
            card.addSource(bookmark.getTitle(), bookmark.getUrl());
        } else {
            first.sources().forEach(s -> card.addSource(s.title(), s.url()));
        }
        return cardRepository.save(card);
    }
}
