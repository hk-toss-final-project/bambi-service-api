package com.bambi.service.briefing;

import com.bambi.service.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 아침 브리핑 주제 <b>선(先)조회</b> 스케줄러 (2026-08-11 유림 요청).
 *
 * <p><b>목적은 주제를 우리가 쓰는 게 아니다 — agent 가 새벽에 미리 알게 하는 것이다.</b>
 * 07:00 에 주제를 정하면 agent 는 그때부터 자료를 실시간으로 모으는데, 실측으로 주제당
 * 44~68초가 든다(3주제면 약 3분). 03:00 에 같은 엔드포인트를 한 번 호출해 두면 agent 가
 * 그 주제로 새벽에 수집을 걸어 두고, 07:00 에는 이미 모인 자료로 바로 쓴다.
 *
 * <p><b>이 스케줄러는 결과를 저장하지 않는다.</b> 저장은 agent 쪽 몫이고
 * ({@code GET /users/{id}/briefing-topics} 응답을 03:00 것으로 재사용), 우리가 하는 일은
 * <b>대상자 명단을 아는 유일한 쪽으로서 호출을 대신 걸어주는 것</b>뿐이다. 그래서 반환값을
 * 버리고 건수만 로그로 남긴다 — 07:00 스케줄러가 같은 엔드포인트를 다시 부르는 구조는 그대로다.
 *
 * <p><b>LLM 호출이 늘지 않는다.</b> 07:00 에 한 번 하던 선정을 03:00 으로 앞당기는 것이고,
 * agent 가 03:00 결과를 재사용하므로 하루 총 선정 횟수는 사용자당 1회로 같다.
 *
 * <p><b>알려진 한계 — 폴백 사용자는 이 예열의 이득이 없다.</b> 위키가 없는 신규 사용자는
 * agent 선정이 비고, 07:00 에 {@link BriefingTopicService} 가 <b>등록 관심사</b>로 폴백한다
 * (폴백 2단계). 그 주제는 agent 가 03:00 에 알 방법이 없어 수집이 안 걸려 있다. 폴백 주제까지
 * 예열하려면 별도 계약(폴백 결과를 agent 에 알리는 경로)이 필요하다 — 08-11 기준 미합의.
 *
 * <p>대상·실패 처리는 {@code GenerationScheduler} 와 같다: 활성 사용자 전원, 한 사용자 실패가
 * 나머지를 막지 않는다. 기본 비활성이며 {@code app.scheduler.briefing-prefetch.enabled=true} 로 켠다.
 */
@Component
@ConditionalOnProperty(name = "app.scheduler.briefing-prefetch.enabled", havingValue = "true")
public class BriefingTopicPrefetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefingTopicPrefetchScheduler.class);

    private final BriefingTopicService briefingTopicService;
    private final UserRepository userRepository;

    public BriefingTopicPrefetchScheduler(BriefingTopicService briefingTopicService,
                                          UserRepository userRepository) {
        this.briefingTopicService = briefingTopicService;
        this.userRepository = userRepository;
    }

    /**
     * 매일 지정 시각(기본 03:00 KST). 07:00 생성 트리거보다 충분히 앞서야 agent 가 수집을 마친다.
     *
     * <p>호출은 사용자별로 순차다. agent 안에서 LLM 이 도는 호출이라 동시에 쏘면 그쪽 큐가 막히고,
     * 새벽 4시간은 순차로 돌아도 남는다(사용자 수십 명 × 수 초).
     */
    @Scheduled(cron = "${app.scheduler.briefing-prefetch.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void prefetchBriefingTopics() {
        List<Long> userIds = userRepository.findAllActiveIds();
        log.info("[BriefingPrefetch] 주제 선조회 시작 users={}", userIds.size());

        int warmed = 0;
        int empty = 0;
        int failed = 0;
        for (Long userId : userIds) {
            try {
                // 반환값은 쓰지 않는다 — 호출 자체가 agent 에게 "이 사용자 주제를 지금 정해 두라"는 신호다.
                if (briefingTopicService.selectFromWikiContext(userId).isEmpty()) {
                    empty++;
                    continue;
                }
                warmed++;
            } catch (Exception e) {
                // 여기서 예외를 올리면 뒤 사용자가 통째로 날아간다. 07:00 은 폴백이 있어 계속 돈다.
                log.warn("[BriefingPrefetch] 선조회 실패 userId={} — 건너뜀", userId, e);
                failed++;
            }
        }
        log.info("[BriefingPrefetch] 주제 선조회 완료 예열={}/{} (주제없음={}, 실패={})",
                warmed, userIds.size(), empty, failed);
    }
}
