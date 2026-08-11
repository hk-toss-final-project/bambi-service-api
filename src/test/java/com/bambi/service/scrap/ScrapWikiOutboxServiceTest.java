package com.bambi.service.scrap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 스크랩 저장·해제 Outbox 이벤트의 연결 관계를 검증한다. */
class ScrapWikiOutboxServiceTest {

    private final ScrapWikiOutboxRepository repository = mock(ScrapWikiOutboxRepository.class);
    private final ScrapWikiOutboxService service = new ScrapWikiOutboxService(repository);

    @Test
    void removeReferencesLatestAddSourceEvent() {
        ScrapWikiOutboxEvent added = ScrapWikiOutboxEvent.add(7L, 42L, "content-1");
        when(repository.findFirstByUserIdAndCardIdAndActionOrderByIdDesc(7L, 42L, "ADD"))
                .thenReturn(Optional.of(added));

        service.enqueueRemove(7L, 42L, "content-1");

        ArgumentCaptor<ScrapWikiOutboxEvent> captor =
                ArgumentCaptor.forClass(ScrapWikiOutboxEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("REMOVE");
        assertThat(captor.getValue().getRelatedSourceEventId())
                .isEqualTo(added.getSourceEventId());
    }
}
