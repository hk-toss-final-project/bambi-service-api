package com.bambi.service.scrap;

import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 스크랩 변경과 Wiki Outbox 기록이 한 애플리케이션 트랜잭션 경계에 묶이는지 검증한다. */
class ScrapServiceTest {

    private final ScrapRepository scraps = mock(ScrapRepository.class);
    private final CardRepository cards = mock(CardRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ScrapWikiOutboxService outbox = mock(ScrapWikiOutboxService.class);
    private final ScrapService service = new ScrapService(scraps, cards, users, outbox);

    @Test
    void newScrapEnqueuesWikiAdd() {
        UUID publicId = UUID.randomUUID();
        Card card = externalCard(publicId);
        when(scraps.insertIgnore(7L, 42L)).thenReturn(1);

        var response = service.scrap(7L, publicId.toString());

        assertThat(response.scrapped()).isTrue();
        verify(outbox).enqueueAdd(7L, 42L, "content-1");
    }

    @Test
    void duplicateScrapDoesNotEnqueueDuplicateWikiAdd() {
        UUID publicId = UUID.randomUUID();
        externalCard(publicId);
        when(scraps.insertIgnore(7L, 42L)).thenReturn(0);

        service.scrap(7L, publicId.toString());

        verify(outbox, never()).enqueueAdd(7L, 42L, "content-1");
    }

    @Test
    void existingScrapRemovalEnqueuesWikiRemove() {
        UUID publicId = UUID.randomUUID();
        externalCard(publicId);
        when(scraps.deleteRelation(7L, 42L)).thenReturn(1);

        var response = service.unscrap(7L, publicId.toString());

        assertThat(response.scrapped()).isFalse();
        verify(outbox).enqueueRemove(7L, 42L, "content-1");
    }

    // ---- 본인 PRIVATE 카드 스크랩 (2026-08-11 확장) ----------------------------

    @Test
    void 본인_PRIVATE_카드는_스크랩할_수_있고_Wiki_반영까지_이어진다() {
        UUID publicId = UUID.randomUUID();
        Card mine = mock(Card.class);
        when(mine.getId()).thenReturn(42L);
        when(mine.getVisibility()).thenReturn("PRIVATE");
        when(mine.getUserId()).thenReturn(7L);              // 소유자 = 요청자
        when(mine.getExternalContentId()).thenReturn("content-1");
        when(cards.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(mine));
        when(scraps.insertIgnore(7L, 42L)).thenReturn(1);

        var response = service.scrap(7L, publicId.toString());

        assertThat(response.scrapped()).isTrue();
        verify(outbox).enqueueAdd(7L, 42L, "content-1");   // agent content-marks 중계도 동일 경로
    }

    @Test
    void 타인_PRIVATE_카드는_여전히_존재_노출_없이_404다() {
        UUID publicId = UUID.randomUUID();
        Card others = mock(Card.class);
        when(others.getVisibility()).thenReturn("PRIVATE");
        when(others.getUserId()).thenReturn(99L);           // 소유자 ≠ 요청자
        when(cards.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(others));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.scrap(7L, publicId.toString()))
                .isInstanceOf(com.bambi.service.common.error.ApiException.class);
        verify(scraps, never()).insertIgnore(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private Card externalCard(UUID publicId) {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(42L);
        when(card.getVisibility()).thenReturn("PUBLIC");
        when(card.getExternalContentId()).thenReturn("content-1");
        when(cards.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(card));
        return card;
    }
}
