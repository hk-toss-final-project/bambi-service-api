package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminAiLogResponse;
import com.bambi.service.admin.dto.AdminDashboardResponse;
import com.bambi.service.report.ReportRepository;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminDashboardService} — 집계 규칙 검증.
 *
 * 수 세기 자체는 DB(count 쿼리)가 하므로 여기선 서비스가 정하는 값,
 * 즉 성공률의 분모·평균 응답시간·최근 실패 추림을 확인한다.
 */
class AdminDashboardServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final AdminAiLogService aiLogService = mock(AdminAiLogService.class);
    private final AdminDashboardService service =
            new AdminDashboardService(userRepository, reportRepository, aiLogService);

    private AdminAiLogResponse log(long id, String status, Integer latencyMs) {
        return new AdminAiLogResponse(
                id, OffsetDateTime.now(), "a@bambi.test", "/internal/v1/users/1/context",
                status, latencyMs);
    }

    private void givenLogs(AdminAiLogResponse... logs) {
        when(aiLogService.listLogs()).thenReturn(List.of(logs));
    }

    @Test
    @DisplayName("성공률의 분모는 끝난 호출 — 처리 중은 빼고 센다")
    void successRateExcludesProcessing() {
        givenLogs(
                log(1, "SUCCESS", 100),
                log(2, "SUCCESS", 300),
                log(3, "FAILED", null),
                log(4, "PROCESSING", null));

        AdminDashboardResponse.AiCalls ai = service.getOverview().ai();

        assertThat(ai.total()).isEqualTo(4);
        assertThat(ai.success()).isEqualTo(2);
        assertThat(ai.failed()).isEqualTo(1);
        assertThat(ai.processing()).isEqualTo(1);
        // 처리 중을 분모에 넣었다면 50%가 됐을 것 — 끝난 3건 중 2건이라 67%.
        assertThat(ai.successRate()).isEqualTo(67);
    }

    @Test
    @DisplayName("호출이 하나도 없으면 성공률 0, 평균 응답시간 null")
    void emptyLogsGiveZeroRateAndNullLatency() {
        givenLogs();

        AdminDashboardResponse.AiCalls ai = service.getOverview().ai();

        assertThat(ai.total()).isZero();
        assertThat(ai.successRate()).isZero();
        assertThat(ai.avgLatencyMs()).isNull();
    }

    @Test
    @DisplayName("평균 응답시간은 성공 호출만 — 실패·처리중의 null 은 섞이지 않는다")
    void averageLatencyCountsSuccessOnly() {
        givenLogs(
                log(1, "SUCCESS", 100),
                log(2, "SUCCESS", 200),
                log(3, "FAILED", 9_999),   // 실패는 오래 걸려도 평균에 안 들어간다
                log(4, "PROCESSING", null));

        assertThat(service.getOverview().ai().avgLatencyMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("최근 실패는 실패만 최신순 5건까지")
    void recentFailuresAreCappedAndFailedOnly() {
        givenLogs(
                log(1, "FAILED", null), log(2, "SUCCESS", 10), log(3, "FAILED", null),
                log(4, "FAILED", null), log(5, "FAILED", null), log(6, "FAILED", null),
                log(7, "FAILED", null));

        List<AdminAiLogResponse> failures = service.getOverview().recentFailures();

        assertThat(failures).hasSize(5);
        assertThat(failures).allMatch(failure -> "FAILED".equals(failure.status()));
        assertThat(failures.get(0).id()).isEqualTo(1L); // 목록이 최신순이라 앞에서 자른다
    }

    @Test
    @DisplayName("사용자 total 은 비활성까지 포함한 누적 — 관리자 목록 길이와 맞춘다")
    void userTotalIncludesInactive() {
        givenLogs();
        when(userRepository.count()).thenReturn(10L);
        when(userRepository.countByDeletedAtIsNull()).thenReturn(7L);
        when(userRepository.countByDeletedAtIsNotNull()).thenReturn(3L);
        when(userRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(2L);
        when(reportRepository.countByDeletedAtIsNull()).thenReturn(42L);
        when(reportRepository.countByDeletedAtIsNullAndCreatedAtGreaterThanEqual(any()))
                .thenReturn(5L);

        AdminDashboardResponse overview = service.getOverview();

        assertThat(overview.users().total()).isEqualTo(10);
        assertThat(overview.users().active()).isEqualTo(7);
        assertThat(overview.users().inactive()).isEqualTo(3);
        assertThat(overview.users().joinedToday()).isEqualTo(2);
        assertThat(overview.reports().total()).isEqualTo(42);
        assertThat(overview.reports().createdToday()).isEqualTo(5);
    }
}
