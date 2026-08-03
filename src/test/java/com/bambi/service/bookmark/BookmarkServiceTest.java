package com.bambi.service.bookmark;

import com.bambi.service.agent.AgentClient;
import com.bambi.service.agent.dto.BookmarkProcessResponse;
import com.bambi.service.agent.dto.CardGenerateResponse;
import com.bambi.service.agent.publish.MockPublishInbox;
import com.bambi.service.bookmark.dto.BookmarkCreateRequest;
import com.bambi.service.bookmark.dto.BookmarkCreateResponse;
import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BookmarkService#create} 의 즉시카드 플래그(app.agent.immediate-card.enabled) 검증.
 * OFF = 저장만 하고 Mock Agent 를 호출하지 않는다(제품 모델: 저장≠생성, 카드는 비동기 발행 경로).
 * ON  = 기존 P0 동기 즉시 카드 경로 유지(데모 안전판 — 코드 제거 금지).
 */
class BookmarkServiceTest {

    private final BookmarkRepository bookmarkRepository = mock(BookmarkRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<MockPublishInbox> publishInbox = mock(ObjectProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private BookmarkService service(boolean immediateCardEnabled) {
        // JPA 가 할당하는 id 를 흉내낸다 — create() 가 이벤트 발행에 bookmark.getId() 를 쓴다.
        when(bookmarkRepository.save(any(Bookmark.class))).thenAnswer(inv -> {
            Bookmark b = inv.getArgument(0);
            ReflectionTestUtils.setField(b, "id", 10L);
            return b;
        });
        return new BookmarkService(bookmarkRepository, cardRepository, agentClient,
                publishInbox, eventPublisher, immediateCardEnabled);
    }

    @Test
    void 즉시카드_OFF면_agent_호출_없이_저장만_하고_card는_null이다() {
        when(bookmarkRepository.existsByUserIdAndUrlAndDeletedAtIsNull(1L, "https://example.com"))
                .thenReturn(false);

        BookmarkCreateResponse res = service(false)
                .create(1L, new BookmarkCreateRequest("https://example.com", "제목", null));

        assertThat(res.card()).isNull();
        assertThat(res.bookmark()).isNotNull();
        verifyNoInteractions(agentClient);          // Mock 즉시카드 경로 미진입
        verifyNoInteractions(cardRepository);       // 카드 저장 없음
        verify(eventPublisher).publishEvent(any(BookmarkSavedEvent.class)); // 위키 클리핑 중계는 그대로
    }

    @Test
    void 즉시카드_ON이면_기존_동기_카드_경로가_유지된다() {
        when(bookmarkRepository.existsByUserIdAndUrlAndDeletedAtIsNull(1L, "https://example.com"))
                .thenReturn(false);
        when(agentClient.processBookmark(any()))
                .thenReturn(new BookmarkProcessResponse("요약", List.of("환율"), List.of(), 0.9));
        when(agentClient.generateCards(any()))
                .thenReturn(new CardGenerateResponse(List.of(new CardGenerateResponse.GeneratedCard(
                        "카드 제목", "카드 요약", "왜 당신에게",
                        List.of(new CardGenerateResponse.Source("출처", "https://example.com"))))));
        when(cardRepository.save(any(Card.class))).thenAnswer(inv -> inv.getArgument(0));

        BookmarkCreateResponse res = service(true)
                .create(1L, new BookmarkCreateRequest("https://example.com", "제목", null));

        assertThat(res.card()).isNotNull();
        assertThat(res.card().title()).isEqualTo("카드 제목");
        verify(cardRepository).save(any(Card.class));
        verify(eventPublisher).publishEvent(any(BookmarkSavedEvent.class));
    }
}
