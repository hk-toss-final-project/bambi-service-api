package com.bambi.service.briefing;

import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link BriefingTopicPrefetchScheduler} — 새벽 선조회의 계약 3가지.
 *
 * <p>이 스케줄러가 지켜야 할 것은 "무엇을 반환하는가"가 아니라 <b>누구를 몇 번 부르는가</b>다.
 * 호출 자체가 agent 에게 보내는 신호이기 때문이다.
 */
class BriefingTopicPrefetchSchedulerTest {

    private final BriefingTopicService briefingTopicService = mock(BriefingTopicService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final BriefingTopicPrefetchScheduler scheduler =
            new BriefingTopicPrefetchScheduler(briefingTopicService, userRepository);

    @Test
    void 활성_사용자마다_정확히_한_번씩_부른다() {
        // 🚨 사용자당 1회가 계약이다 — 여러 번 부르면 LLM 호출이 늘어난다("횟수는 안 늘어난다"가 전제).
        when(userRepository.findAllActiveIds()).thenReturn(List.of(1L, 2L, 3L));
        when(briefingTopicService.selectFromWikiContext(anyLong())).thenReturn(List.of("폭염"));

        scheduler.prefetchBriefingTopics();

        verify(briefingTopicService).selectFromWikiContext(1L);
        verify(briefingTopicService).selectFromWikiContext(2L);
        verify(briefingTopicService).selectFromWikiContext(3L);
        verifyNoMoreInteractions(briefingTopicService);
    }

    @Test
    void 폴백_경로는_부르지_않는다() {
        // 예열 대상은 agent 선정(1단계)뿐이다. resolveForMorningBriefing 을 부르면 등록 관심사
        // 폴백까지 타는데, 그 주제는 agent 에 전달되지 않아 수집이 걸리지 않는다(예열 효과 0).
        when(userRepository.findAllActiveIds()).thenReturn(List.of(1L));
        when(briefingTopicService.selectFromWikiContext(1L)).thenReturn(List.of());

        scheduler.prefetchBriefingTopics();

        verify(briefingTopicService, never()).resolveForMorningBriefing(anyLong());
    }

    @Test
    void 한_사용자_실패가_나머지를_막지_않는다() {
        when(userRepository.findAllActiveIds()).thenReturn(List.of(1L, 2L, 3L));
        when(briefingTopicService.selectFromWikiContext(eq(2L)))
                .thenThrow(new RuntimeException("agent down"));
        when(briefingTopicService.selectFromWikiContext(eq(1L))).thenReturn(List.of("폭염"));
        when(briefingTopicService.selectFromWikiContext(eq(3L))).thenReturn(List.of("퇴근"));

        scheduler.prefetchBriefingTopics();

        // 2번이 터져도 3번까지 돈다 — 새벽 배치가 중간에 멈추면 남은 사용자는 07:00 에 그대로 느려진다.
        verify(briefingTopicService, times(3)).selectFromWikiContext(anyLong());
    }
}
