package com.bambi.service.interest;

import com.bambi.service.interest.dto.InterestRequest;
import com.bambi.service.interest.taxonomy.InterestTaxonomyService;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link InterestService} — 관심사 변경 시 {@link InterestChangedEvent} 를 발행하는지 검증.
 * 이 이벤트가 커밋 후 agent 컨텍스트 재동기화를 트리거한다(프론트 sync 호출 의존 제거).
 */
class InterestServiceTest {

    private final InterestRepository repo = mock(InterestRepository.class);
    private final InterestTaxonomyService taxonomy = mock(InterestTaxonomyService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final com.bambi.service.wiki.BlockedWikiTagRepository blockedTags =
            mock(com.bambi.service.wiki.BlockedWikiTagRepository.class);
    private final InterestService service =
            new InterestService(repo, taxonomy, events, blockedTags);

    @Test
    void 관심사_생성시_변경_이벤트를_발행한다() {
        InterestRequest req = mock(InterestRequest.class);
        when(req.isTaxonomySelection()).thenReturn(false);
        when(req.name()).thenReturn("소라관심사");
        when(repo.existsByUserIdAndNameAndDeletedAtIsNull(1L, "소라관심사")).thenReturn(false);

        service.create(1L, req);

        verify(events).publishEvent(any(InterestChangedEvent.class));
    }

    @Test
    void 관심사_삭제시_변경_이벤트를_발행한다() {
        Interest interest = mock(Interest.class);
        when(repo.findByIdAndUserIdAndDeletedAtIsNull(5L, 1L)).thenReturn(Optional.of(interest));

        service.delete(1L, 5L);

        verify(interest).softDelete();
        verify(events).publishEvent(any(InterestChangedEvent.class));
    }
}
