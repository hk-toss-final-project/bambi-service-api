package com.bambi.service.worker;

import com.bambi.service.agent.publish.PublishSnapshotClient;
import com.bambi.service.agent.publish.dto.AckRequest;
import com.bambi.service.agent.publish.dto.ClaimResponse;
import com.bambi.service.agent.publish.dto.PublishItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PublishPollingWorker} 실패 분류 — 잘못된 페이로드는 영구(retryable=false),
 * 일시 장애는 재시도(retryable=true)로 ACK 해 무한 재시도 루프를 막는다.
 */
class PublishPollingWorkerTest {

    private final PublishSnapshotClient client = mock(PublishSnapshotClient.class);
    private final PublishProcessingService processing = mock(PublishProcessingService.class);
    private final PublishPollingWorker worker = new PublishPollingWorker(client, processing);

    private void claimReturnsOneItem() {
        PublishItem item = new PublishItem("c1", "mock-user-001", 1, "hash-1",
                "제목", "요약", "본문", List.of(), List.of(), List.of(), null);
        when(client.claim(any())).thenReturn(
                new ClaimResponse("batch-1", OffsetDateTime.parse("2026-07-30T00:00:00Z"), List.of(item)));
    }

    @Test
    void 잘못된_페이로드_비숫자userId는_영구실패로_ACK한다() {
        claimReturnsOneItem();
        // 비숫자 user_id → upsert 가 IllegalArgumentException
        doThrow(new IllegalArgumentException("agent user_id 가 service-db 사용자 ID 형식이 아님: mock-user-001"))
                .when(processing).upsert(any());

        worker.poll();

        ArgumentCaptor<AckRequest> captor = ArgumentCaptor.forClass(AckRequest.class);
        verify(client).ack(anyString(), captor.capture());
        AckRequest.AckItem ack = captor.getValue().items().get(0);
        assertThat(ack.status()).isEqualTo("failed");
        assertThat(ack.retryable()).isFalse();   // 재전달해도 계속 실패 → 무한 루프 방지
    }

    @Test
    void 일시_장애는_재시도가능으로_ACK한다() {
        claimReturnsOneItem();
        // DB 일시 장애 등 → 재시도하면 성공할 수 있음
        doThrow(new RuntimeException("DB connection lost")).when(processing).upsert(any());

        worker.poll();

        ArgumentCaptor<AckRequest> captor = ArgumentCaptor.forClass(AckRequest.class);
        verify(client).ack(anyString(), captor.capture());
        AckRequest.AckItem ack = captor.getValue().items().get(0);
        assertThat(ack.status()).isEqualTo("failed");
        assertThat(ack.retryable()).isTrue();
    }

    @Test
    void 성공하면_published로_ACK한다() {
        claimReturnsOneItem();
        when(processing.upsert(any())).thenReturn(true);

        worker.poll();

        ArgumentCaptor<AckRequest> captor = ArgumentCaptor.forClass(AckRequest.class);
        verify(client).ack(anyString(), captor.capture());
        AckRequest.AckItem ack = captor.getValue().items().get(0);
        assertThat(ack.status()).isEqualTo("published");
    }
}
