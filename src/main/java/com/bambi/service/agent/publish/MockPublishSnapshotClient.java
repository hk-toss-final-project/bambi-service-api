package com.bambi.service.agent.publish;

import com.bambi.service.agent.publish.dto.AckRequest;
import com.bambi.service.agent.publish.dto.ClaimRequest;
import com.bambi.service.agent.publish.dto.ClaimResponse;
import com.bambi.service.agent.publish.dto.PublishItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P0 Mock — 인프로세스 큐로 §4 claim/ack 계약을 흉내낸다.
 * enqueue(도메인 트리거) → 큐 적재 → claim(워커) → lease 보관 → ack 로 확정 제거.
 * ack 안 된 lease 는 만료 시 다시 claim 대상으로 복귀(유실 없음)한다.
 * 스레드 안전: 큐/lease 맵 모두 concurrent 컬렉션. (분산 락은 P1/Redis)
 *
 * <p>{@code app.agent.publish.mode=mock}(미설정 기본)일 때만 등록된다.
 * {@code http}면 대신 {@link RestClientPublishSnapshotClient}(실제 agent-api 호출)가 뜬다.
 * 두 구현이 같은 {@link PublishSnapshotClient} 빈이라 조건으로 하나만 올려 충돌을 막는다.
 */
@Component
@ConditionalOnProperty(name = "app.agent.publish.mode", havingValue = "mock", matchIfMissing = true)
public class MockPublishSnapshotClient implements PublishSnapshotClient, MockPublishInbox {

    private static final Logger log = LoggerFactory.getLogger(MockPublishSnapshotClient.class);

    private final ConcurrentLinkedQueue<PublishItem> ready = new ConcurrentLinkedQueue<>();
    private final Map<String, LeasedBatch> leased = new ConcurrentHashMap<>();
    private final AtomicLong batchSeq = new AtomicLong();

    private record LeasedBatch(List<PublishItem> items, OffsetDateTime expiresAt) {
    }

    @Override
    public void enqueue(Long userId, String contentId, String title,
                        String sourceTitle, String sourceUrl) {
        PublishItem item = new PublishItem(
                contentId, String.valueOf(userId), 1,
                "hash-" + contentId,
                title,
                "저장한 콘텐츠를 바탕으로 agent 가 생성한 심화 브리핑입니다. (Mock 비동기 발행)",
                "본문(Mock). 실제 LLM 연동(P1) 시 교체.",
                List.of(new PublishItem.Citation(sourceTitle, sourceUrl)),
                List.of("Mock 관심사"),          // tags(topic 에코) — 실제 agent 는 generation_requests.topic
                List.of("Mock 콘텐츠태그"),       // content_tags — 리포트 내용 기반(카드 노출용)
                null,                           // report_type — 소라 게이트웨이 롤아웃 전 상태 재현(null 관용)
                "",                             // Mock 저장 경로는 생성 pending과 연결되지 않음
                null,                           // generation_topic — 근사 매칭 대상이 아님
                null);                          // created_at — 없으면 근사 매칭을 아예 시도하지 않는다
        ready.add(item);
        log.info("[MockPublish] enqueue contentId={}, userId={}", contentId, userId);
    }

    @Override
    public ClaimResponse claim(ClaimRequest request) {
        expireLeases();
        if (ready.isEmpty()) {
            return ClaimResponse.empty();
        }
        List<PublishItem> batch = new ArrayList<>();
        for (int i = 0; i < request.limit(); i++) {
            PublishItem item = ready.poll();
            if (item == null) {
                break;
            }
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return ClaimResponse.empty();
        }
        String batchId = "batch-" + batchSeq.incrementAndGet();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(request.leaseSeconds());
        leased.put(batchId, new LeasedBatch(batch, expiresAt));
        log.info("[MockPublish] claim batchId={}, items={}, worker={}",
                batchId, batch.size(), request.workerId());
        return new ClaimResponse(batchId, expiresAt, batch);
    }

    @Override
    public void ack(String batchId, AckRequest request) {
        LeasedBatch batch = leased.remove(batchId);
        if (batch == null) {
            log.warn("[MockPublish] ack unknown/expired batchId={} (재시도 안전 — 무시)", batchId);
            return;
        }
        // published 아닌(=retryable failed) 항목은 다시 ready 로 되돌린다.
        List<String> published = request.items().stream()
                .filter(i -> "published".equals(i.status()))
                .map(AckRequest.AckItem::contentId)
                .toList();
        for (PublishItem item : batch.items()) {
            if (!published.contains(item.contentId())) {
                ready.add(item);
            }
        }
        log.info("[MockPublish] ack batchId={}, published={}/{}",
                batchId, published.size(), batch.items().size());
    }

    /** lease 만료 배치를 ready 로 복귀 (처리 중 워커가 죽어도 유실 없음). */
    private void expireLeases() {
        OffsetDateTime now = OffsetDateTime.now();
        leased.forEach((batchId, batch) -> {
            if (batch.expiresAt().isBefore(now) && leased.remove(batchId, batch)) {
                ready.addAll(batch.items());
                log.info("[MockPublish] lease 만료 복귀 batchId={}, items={}", batchId, batch.items().size());
            }
        });
    }
}
