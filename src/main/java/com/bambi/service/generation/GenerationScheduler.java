package com.bambi.service.generation;

import com.bambi.service.generation.dto.GenerationRequest;
import com.bambi.service.user.UserRepository;
import com.bambi.service.wiki.AgentWikiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 콘텐츠 생성 트리거 스케줄러 (송우 가이드 §3.4 "사용자 지정 시간 스케줄 = service 책임").
 * 지정 시각에 사용자별 생성 Job 을 agent 에 요청한다(정시 호출 방식 — scheduled_at 은 생략).
 *
 * <p>멱등키 = {날짜윈도우}-{userId}-{contentType} (예: 2026-07-30-23-interest_news_card)
 * → 스케줄러 재시도·중복 실행에도 agent 가 Job 을 한 번만 만든다.
 *
 * <p>기본 비활성(app.scheduler.generation.enabled=true 로 켬). 클라이언트 구현은
 * app.agent.generation.mode(mock|http)로 고르며, http 면 {@link RestClientGenerationClient} 가 실제로 호출한다.
 *
 * <p>대상은 활성 사용자 전원이다 — 온보딩에서 고른 관심사가 위키에 편입돼 가입자는 위키를 갖기 때문
 * (2026-08-04 송우 확인, 편입 로직은 agent 쪽에서 보강). 혹시 컨텍스트가 없는 사용자는 agent 가
 * 409/USER_CONTEXT_REQUIRED 를 내는데, 아래 per-user try/catch 가 그 사용자만 건너뛴다.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.generation.enabled", havingValue = "true")
public class GenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(GenerationScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GenerationClient generationClient;
    private final UserRepository userRepository;
    private final AgentWikiClient wikiClient;
    private final GenerationPendingService pendingService;
    private final String contentType;

    public GenerationScheduler(
            GenerationClient generationClient,
            UserRepository userRepository,
            AgentWikiClient wikiClient,
            GenerationPendingService pendingService,
            @Value("${app.scheduler.generation.content-type:interest_news_card}") String contentType) {
        this.generationClient = generationClient;
        this.userRepository = userRepository;
        this.wikiClient = wikiClient;
        this.pendingService = pendingService;
        this.contentType = contentType;
    }

    /** 매일 지정 시각(기본 07:00 KST)에 실행. 한 사용자 실패가 나머지를 막지 않는다. */
    @Scheduled(cron = "${app.scheduler.generation.cron:0 0 7 * * *}", zone = "Asia/Seoul")
    public void triggerDailyGeneration() {
        LocalDate window = LocalDate.now(KST);
        List<Long> userIds = userRepository.findAllActiveIds();
        log.info("[GenerationScheduler] 생성 트리거 시작 window={}, users={}", window, userIds.size());

        int requested = 0;
        int skipped = 0;
        for (Long userId : userIds) {
            try {
                // 검색 주제 = 사용자 대표 관심사(위키 태그 score 최고). topic 은 라벨이 아니라 실제 검색어라
                // 고정 문구를 쓰면 엉뚱한 기사를 물어온다(유림 확인 08-05). 관심사 없으면 생성할 게 없어 건너뛴다.
                String topic = wikiClient.getTags(userId).topTopic().orElse(null);
                if (topic == null) {
                    skipped++;
                    continue;
                }
                GenerationRequest request = new GenerationRequest(
                        idempotencyKey(window, userId, contentType),
                        topic,
                        contentType,
                        null,   // language — 컨텍스트 선호 언어 사용
                        null,   // scheduled_at — 정시 호출이라 즉시 실행(예약 필요 시 +09:00 포함해 지정)
                        GenerationPendingService.REPORT_TYPE_MORNING_BRIEFING);  // agent 가 snapshot 에 에코
                String agentJobId = generationClient.requestGeneration(userId, request);
                // 접수 성공 후 펜딩 영속화(MORNING_BRIEFING) — 온디맨드와 같은 결정적 id 규칙.
                // 재실행·재시도는 같은 멱등키 → 같은 id 라 중복 행이 생기지 않는다.
                pendingService.register(userId, request.idempotencyKey(),
                        GenerationPendingService.REPORT_TYPE_MORNING_BRIEFING, topic, contentType, agentJobId);
                requested++;
            } catch (Exception e) {
                // agent 다운/일부 실패는 전체를 막지 않는다(컨텍스트 동기화 패턴과 동일).
                log.warn("[GenerationScheduler] 사용자 생성 요청 실패 userId={} — 건너뜀", userId, e);
            }
        }
        log.info("[GenerationScheduler] 생성 트리거 완료 요청={}/{} (관심사없음 건너뜀={})",
                requested, userIds.size(), skipped);
    }

    /**
     * 멱등키 = {날짜윈도우}-{userId}-{contentType}.
     * 같은 날·같은 사용자·같은 유형은 항상 같은 키 → agent Job 중복 생성 방지.
     */
    static String idempotencyKey(LocalDate window, long userId, String contentType) {
        return window + "-" + userId + "-" + contentType;
    }
}
