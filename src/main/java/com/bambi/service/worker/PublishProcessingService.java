package com.bambi.service.worker;

import com.bambi.service.agent.publish.dto.PublishItem;
import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.generation.GenerationPendingService;
import com.bambi.service.notification.NotificationService;
import com.bambi.service.report.Report;
import com.bambi.service.report.ReportRepository;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
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
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final GenerationPendingService pendingService;

    public PublishProcessingService(
            CardRepository cardRepository,
            ReportRepository reportRepository,
            NotificationService notificationService,
            UserRepository userRepository,
            GenerationPendingService pendingService) {
        this.cardRepository = cardRepository;
        this.reportRepository = reportRepository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.pendingService = pendingService;
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
            // 신규 카드가 아니므로 근사 매칭은 걸지 않는다 — 연결 키가 있을 때만 닫힌다(우석 가드 2).
            pendingService.completeFromSnapshot(
                    userId, item, existingCard.get().getPublicId(), false);
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
        card.replaceInterestTags(item.interestTags());   // content_tags 우선(없으면 topic 폴백) 통째 교체
        card.replaceTaxonomyTopics(item.taxonomyVersion(), item.taxonomyTopicIds());   // 추천 매칭용 topic_key(관용)
        card.applyReportType(item.normalizedReportType());   // 생성 유형(없으면 null 유지 — 관용)

        if (isNew) {
            // 사용자 설정(V17)을 신규 발행 카드에만 적용한다. 갱신 카드는 사용자가 이미 토글했을 수 있어 건드리지 않는다.
            User user = userRepository.findById(userId).orElse(null);
            if (user != null) {
                // 발행 카드 기본 공개범위 = 사용자 설정(PRIVATE/PUBLIC). 발행 후 개별 토글 가능.
                card.changeVisibility(user.getDefaultCardVisibility());
            }   // user 조회 실패(비정상) 시엔 종전 기본값 PRIVATE 유지.

            try {
                cardRepository.save(card);
            } catch (DataIntegrityViolationException e) {
                // 동시 워커/재시도로 유니크 인덱스 충돌 → 이미 발행된 것으로 간주(멱등).
                log.info("[PublishWorker] 유니크 충돌 → 멱등 처리 contentId={}", item.contentId());
                return true;
            }
            // REPORT_READY 알림: 사용자가 수신 OFF 면 알림을 "만들지 않는다"(만들고 숨기는 게 아님).
            if (user == null || user.isReportReadyNotification()) {
                notificationService.notifyReportReady(
                        userId,
                        item.contentId(),
                        item.version(),
                        item.title(),
                        item.summary(),
                        report.getPublicId(),
                        item.normalizedReportType());
            } else {
                log.info("[PublishWorker] 알림수신 OFF → REPORT_READY 생성 생략 userId={} contentId={}",
                        userId, item.contentId());
            }
        }
        log.info("[PublishWorker] 리포트+카드 {} contentId={} (v{}), reportId={}",
                isNew ? "발행" : "갱신", item.contentId(), item.version(), report.getId());
        // 연결 키가 있으면 신규·갱신 모두 그것으로 정확히 닫는다. 키 없는 구 Snapshot 은
        // 신규 발행일 때만 근사 매칭한다 — 재발행이 나중에 접수된 펜딩을 오완료하지 않게(우석 가드 2).
        pendingService.completeFromSnapshot(userId, item, card.getPublicId(), isNew);
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
            report.applyReportType(item.normalizedReportType());   // 생성 유형(없으면 null — 관용)
            report.applyChangeHistoryEnabled(item.changeHistoryEnabled());   // body 폼 신호(미도착 = false)
            applyCoverImage(report, item);
            return reportRepository.save(report);   // id 확보(카드가 참조)
        }
        report.updateBody(item.version(), item.title(), item.summary(), item.body());
        addCitations(report, item);   // updateBody 가 인용을 비웠으므로 다시 채운다
        report.applyReportType(item.normalizedReportType());   // null 재발행은 기존 유형 유지
        // 본문을 갈아끼웠으니 폼 신호도 함께 갈아끼운다 — 짝이 어긋나면 화면이 깨진다(reportType 과 정책이 다름).
        report.applyChangeHistoryEnabled(item.changeHistoryEnabled());
        applyCoverImage(report, item);
        return report;   // dirty checking
    }

    /** 대표 이미지 선택 필드는 nullable이며, 유효하지 않으면 이전 이미지를 함께 지운다. */
    private void applyCoverImage(Report report, PublishItem item) {
        PublishItem.CoverImage cover = item.normalizedCoverImage();
        report.applyCoverImage(
                cover != null ? cover.url() : null,
                cover != null ? cover.sourceUrl() : null,
                cover != null ? cover.sourceTitle() : null,
                cover != null ? cover.reference() : null);
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
