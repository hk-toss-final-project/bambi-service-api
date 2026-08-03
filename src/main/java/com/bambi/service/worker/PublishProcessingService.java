package com.bambi.service.worker;

import com.bambi.service.agent.publish.dto.PublishItem;
import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.report.Report;
import com.bambi.service.report.ReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 발행 항목 1건을 service-db 로 멱등 Upsert (§4: content_id + version 키).
 * 본문은 service.reports 에, 요약은 service.cards 에 저장하고 카드가 리포트를 참조한다(§3.1).
 * 항목별 독립 트랜잭션 — 배치 전체를 한 트랜잭션으로 묶지 않는다(부분 성공 허용).
 *
 * <p>진짜 upsert: 같은 (userId, content_id) 가
 *  - 없으면 → 리포트+카드 신규 저장
 *  - 있고 수신 version 이 더 크면 → 본문·요약 갱신 (agent 스냅샷 갱신 발행)
 *  - 같거나 작으면 → skip (재-claim/중복/구버전 도착)
 */
@Service
public class PublishProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PublishProcessingService.class);

    private final CardRepository cardRepository;
    private final ReportRepository reportRepository;

    public PublishProcessingService(CardRepository cardRepository, ReportRepository reportRepository) {
        this.cardRepository = cardRepository;
        this.reportRepository = reportRepository;
    }

    /**
     * @return true = 발행 반영됨(신규/갱신) 또는 이미 최신이라 skip. 실패는 예외로 전파.
     */
    @Transactional
    public boolean upsert(PublishItem item) {
        Long userId = item.userIdAsLong();
        Optional<Card> existingCard = cardRepository.findByUserIdAndExternalContentId(userId, item.contentId());

        // 버전 게이트: 카드의 저장 version 을 기준으로 최신 여부 판단(카드·리포트는 같은 content_id·version).
        if (existingCard.isPresent() && !isNewer(item.version(), existingCard.get().getExternalVersion())) {
            log.info("[PublishWorker] 이미 최신 skip contentId={} (수신 v{}, 저장 v{})",
                    item.contentId(), item.version(), existingCard.get().getExternalVersion());
            return true;
        }

        // 이 version 이 이긴다 → 본문(리포트) upsert 후 카드가 참조한다.
        Report report = upsertReport(userId, item);

        // 신규면 새 카드, 갱신이면 기존 카드 — 이후 필드 반영(출처·리포트 링크·태그)은 공통.
        boolean isNew = existingCard.isEmpty();
        Card card = existingCard.orElseGet(() -> Card.fromExternal(
                userId, item.contentId(), item.version(), item.title(), item.summary(), null));
        if (!isNew) {
            card.updateExternal(item.version(), item.title(), item.summary());
        }
        addSources(card, item);
        card.linkReport(report.getId());
        card.replaceInterestTags(item.tags());   // 발행 태그(topic) 통째 교체 — 재수신 시 최신으로, 없으면 비움

        if (isNew) {
            try {
                cardRepository.save(card);
            } catch (DataIntegrityViolationException e) {
                // 동시 워커/재시도로 유니크 인덱스 충돌 → 이미 발행된 것으로 간주(멱등).
                log.info("[PublishWorker] 유니크 충돌 → 멱등 처리 contentId={}", item.contentId());
                return true;
            }
        }
        log.info("[PublishWorker] 리포트+카드 {} contentId={} (v{}), reportId={}",
                isNew ? "발행" : "갱신", item.contentId(), item.version(), report.getId());
        return true;   // 갱신은 dirty checking, 신규는 save 로 flush
    }

    /** 리포트(본문) upsert. 없으면 생성(저장→id 확보), 있으면 본문·인용 교체 후 반환. */
    private Report upsertReport(Long userId, PublishItem item) {
        Report report = reportRepository.findByUserIdAndExternalContentId(userId, item.contentId())
                .orElse(null);
        if (report == null) {
            report = Report.fromExternal(
                    userId, item.contentId(), item.version(), item.title(), item.summary(), item.body());
            addCitations(report, item);
            return reportRepository.save(report);   // id 확보(카드가 참조)
        }
        report.updateBody(item.version(), item.title(), item.summary(), item.body());
        addCitations(report, item);   // updateBody 가 인용을 비웠으므로 다시 채운다
        return report;   // dirty checking
    }

    /** 수신 version 이 저장본보다 큰가. version 없으면(=null) 갱신하지 않는다(구현 안전). */
    private boolean isNewer(Integer incoming, Integer stored) {
        if (incoming == null) {
            return false;
        }
        if (stored == null) {
            return true;
        }
        return incoming > stored;
    }

    private void addSources(Card card, PublishItem item) {
        if (item.citations() != null) {
            item.citations().forEach(c -> card.addSource(c.title(), c.url()));
        }
    }

    private void addCitations(Report report, PublishItem item) {
        if (item.citations() != null) {
            item.citations().forEach(c -> report.addCitation(c.title(), c.url()));
        }
    }
}
