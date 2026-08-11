package com.bambi.service.worker;

import com.bambi.service.agent.AgentGateway;
import com.bambi.service.agent.dto.AgentAcceptedJob;
import com.bambi.service.agent.dto.AgentContentMarkDeletionRequest;
import com.bambi.service.agent.dto.AgentContentMarkRequest;
import com.bambi.service.scrap.ScrapWikiOutboxEvent;
import com.bambi.service.scrap.ScrapWikiOutboxService;
import com.bambi.service.wiki.WikiBuildOperationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/** DB Outbox를 폴링해 스크랩 저장·해제를 Agent 개인 Wiki에 전달한다. */
@Component
@ConditionalOnProperty(name = "app.worker.scrap-wiki.enabled", havingValue = "true", matchIfMissing = true)
public class ScrapWikiOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(ScrapWikiOutboxWorker.class);

    private final ScrapWikiOutboxService outbox;
    private final AgentGateway agentGateway;
    private final WikiBuildOperationService wikiOperations;

    @Value("${app.worker.scrap-wiki.batch-limit:20}")
    private int batchLimit;

    public ScrapWikiOutboxWorker(
            ScrapWikiOutboxService outbox,
            AgentGateway agentGateway,
            WikiBuildOperationService wikiOperations) {
        this.outbox = outbox;
        this.agentGateway = agentGateway;
        this.wikiOperations = wikiOperations;
    }

    /** 점유한 이벤트를 건별 전송하고 실패한 건만 지연 재시도한다. */
    @Scheduled(fixedDelayString = "${app.worker.scrap-wiki.poll-interval-ms:3000}",
            initialDelayString = "${app.worker.scrap-wiki.initial-delay-ms:5000}")
    public void poll() {
        List<ScrapWikiOutboxEvent> events = outbox.claim(batchLimit);
        for (ScrapWikiOutboxEvent event : events) {
            try {
                AgentAcceptedJob accepted = relay(event);
                wikiOperations.register(
                        event.getUserId(), event.getSourceEventId().toString(), accepted);
                outbox.markDelivered(event.getId(), accepted);
            } catch (Exception error) {
                log.warn(
                        "[ScrapWikiOutbox] Agent 전달 실패 — 재시도(eventId={}, action={})",
                        event.getId(), event.getAction(), error);
                outbox.markRetry(event.getId(), error);
            }
        }
    }

    private AgentAcceptedJob relay(ScrapWikiOutboxEvent event) {
        if ("ADD".equals(event.getAction())) {
            return agentGateway.relayContentMark(
                    event.getUserId(),
                    new AgentContentMarkRequest(
                            event.getSourceEventId().toString(), event.getExternalContentId()));
        }
        return agentGateway.relayContentMarkDeletion(
                event.getUserId(),
                new AgentContentMarkDeletionRequest(
                        event.getSourceEventId().toString(),
                        event.getRelatedSourceEventId().toString(),
                        event.getExternalContentId()));
    }
}
