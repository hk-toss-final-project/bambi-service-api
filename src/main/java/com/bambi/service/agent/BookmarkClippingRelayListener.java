package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentClippingRequest;
import com.bambi.service.agent.dto.AgentUrlSourceRequest;
import com.bambi.service.bookmark.BookmarkSavedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * 북마크 저장 완료 → agent 위키 원천 처리로 중계.
 *
 * <p>저장 트랜잭션이 커밋된 뒤(AFTER_COMMIT) 실행하고, 중계 실패는 삼켜 저장 자체는 막지 않는다
 * (가입 컨텍스트 동기화와 동일 정책). agent 클리핑 계약이 URL·본문을 모두 요구하므로 분기한다:
 * <ul>
 *   <li>URL + 본문 → clippings (웹 클리핑)
 *   <li>URL 만 → urls (URL 원천)
 *   <li>본문만(URL 없음) → 중계 대상 아님(둘 다 URL 필수) — 건너뜀
 * </ul>
 */
@Component
public class BookmarkClippingRelayListener {

    private static final Logger log = LoggerFactory.getLogger(BookmarkClippingRelayListener.class);

    private final AgentGateway agentGateway;

    public BookmarkClippingRelayListener(AgentGateway agentGateway) {
        this.agentGateway = agentGateway;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookmarkSaved(BookmarkSavedEvent event) {
        String sourceEventId = "bookmark-" + event.bookmarkId();
        boolean hasUrl = StringUtils.hasText(event.url());
        boolean hasContent = StringUtils.hasText(event.content());

        try {
            if (hasUrl && hasContent) {
                agentGateway.relayClipping(event.userId(), new AgentClippingRequest(
                        sourceEventId, event.url(), titleOrFallback(event), event.content(), null, java.util.List.of()));
            } else if (hasUrl) {
                agentGateway.relayUrlSource(event.userId(), AgentUrlSourceRequest.of(sourceEventId, event.url()));
            } else {
                // 본문만 있는 메모 — clippings·urls 둘 다 URL 필수라 현재 중계 대상 아님.
                log.debug("[BookmarkRelay] URL 없는 저장 — 위키 중계 건너뜀 (bookmarkId={})", event.bookmarkId());
            }
        } catch (Exception e) {
            // 저장은 이미 커밋됨 — 중계 실패가 사용자 저장을 되돌리지 않는다.
            log.warn("[BookmarkRelay] agent 위키 중계 실패 (bookmarkId={}) — 저장은 유지", event.bookmarkId(), e);
        }
    }

    /** 클리핑 title 은 필수(비어 있으면 안 됨) — 제목 없으면 대체 문구를 넣는다. */
    private String titleOrFallback(BookmarkSavedEvent event) {
        return StringUtils.hasText(event.title()) ? event.title() : "저장한 자료";
    }
}
