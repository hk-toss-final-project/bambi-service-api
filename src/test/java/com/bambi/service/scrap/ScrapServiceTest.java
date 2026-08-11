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

    private Card externalCard(UUID publicId) {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(42L);
        when(card.getVisibility()).thenReturn("PUBLIC");
        when(card.getExternalContentId()).thenReturn("content-1");
        when(cards.findByPublicIdAndDeletedAtIsNull(publicId)).thenReturn(Optional.of(card));
        return card;
    }
}
