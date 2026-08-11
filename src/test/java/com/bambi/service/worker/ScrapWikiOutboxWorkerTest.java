package com.bambi.service.worker;

import com.bambi.service.agent.AgentGateway;
import com.bambi.service.agent.dto.AgentAcceptedJob;
import com.bambi.service.agent.dto.AgentContentMarkDeletionRequest;
import com.bambi.service.agent.dto.AgentContentMarkRequest;
import com.bambi.service.scrap.ScrapWikiOutboxEvent;
import com.bambi.service.scrap.ScrapWikiOutboxService;
import com.bambi.service.wiki.WikiBuildOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 스크랩 Wiki Outbox 폴러의 Agent 전달·상태 추적·재시도를 검증한다. */
class ScrapWikiOutboxWorkerTest {

    private final ScrapWikiOutboxService outbox = mock(ScrapWikiOutboxService.class);
    private final AgentGateway gateway = mock(AgentGateway.class);
    private final WikiBuildOperationService wikiOperations = mock(WikiBuildOperationService.class);
    private final ScrapWikiOutboxWorker worker = worker();

    @Test
    void addEventRegistersAcceptedWikiJobAndCompletesOutbox() {
        UUID sourceEventId = UUID.randomUUID();
        ScrapWikiOutboxEvent event = event(1L, sourceEventId, null, "ADD");
        AgentAcceptedJob accepted = new AgentAcceptedJob("job-1", "queued", "source-1", "version-1");
        when(outbox.claim(20)).thenReturn(List.of(event));
        when(gateway.relayContentMark(
                7L, new AgentContentMarkRequest(sourceEventId.toString(), "content-1")))
                .thenReturn(accepted);

        worker.poll();

        verify(wikiOperations).register(7L, sourceEventId.toString(), accepted);
        verify(outbox).markDelivered(1L, accepted);
    }

    @Test
    void removeEventCarriesOriginalAddEventId() {
        UUID sourceEventId = UUID.randomUUID();
        UUID relatedEventId = UUID.randomUUID();
        ScrapWikiOutboxEvent event = event(2L, sourceEventId, relatedEventId, "REMOVE");
        AgentAcceptedJob accepted = new AgentAcceptedJob("job-2", "queued");
        when(outbox.claim(20)).thenReturn(List.of(event));
        when(gateway.relayContentMarkDeletion(
                7L,
                new AgentContentMarkDeletionRequest(
                        sourceEventId.toString(), relatedEventId.toString(), "content-1")))
                .thenReturn(accepted);

        worker.poll();

        verify(outbox).markDelivered(2L, accepted);
    }

    @Test
    void agentFailureReturnsEventToRetry() {
        UUID sourceEventId = UUID.randomUUID();
        ScrapWikiOutboxEvent event = event(3L, sourceEventId, null, "ADD");
        RuntimeException failure = new RuntimeException("agent down");
        when(outbox.claim(20)).thenReturn(List.of(event));
        when(gateway.relayContentMark(
                7L, new AgentContentMarkRequest(sourceEventId.toString(), "content-1")))
                .thenThrow(failure);

        worker.poll();

        verify(outbox).markRetry(3L, failure);
    }

    private ScrapWikiOutboxWorker worker() {
        ScrapWikiOutboxWorker result = new ScrapWikiOutboxWorker(outbox, gateway, wikiOperations);
        ReflectionTestUtils.setField(result, "batchLimit", 20);
        return result;
    }

    private ScrapWikiOutboxEvent event(
            Long id, UUID sourceEventId, UUID relatedEventId, String action) {
        ScrapWikiOutboxEvent event = mock(ScrapWikiOutboxEvent.class);
        when(event.getId()).thenReturn(id);
        when(event.getSourceEventId()).thenReturn(sourceEventId);
        when(event.getRelatedSourceEventId()).thenReturn(relatedEventId);
        when(event.getUserId()).thenReturn(7L);
        when(event.getAction()).thenReturn(action);
        when(event.getExternalContentId()).thenReturn("content-1");
        return event;
    }
}
