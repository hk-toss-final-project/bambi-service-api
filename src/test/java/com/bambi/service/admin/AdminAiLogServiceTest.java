package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminAiLogDetailResponse;
import com.bambi.service.admin.dto.AdminAiLogResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AdminAiLogService#getLogDetail} 검증 — 상세 조회의 없음/있음 분기.
 */
class AdminAiLogServiceTest {

    private final AiRequestLogRepository requestRepo = mock(AiRequestLogRepository.class);
    private final AiResponseLogRepository responseRepo = mock(AiResponseLogRepository.class);
    private final AdminAiLogService service = new AdminAiLogService(requestRepo, responseRepo);

    @Test
    void 요청_로그가_없으면_NOT_FOUND() {
        when(requestRepo.findById(anyLong())).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(() -> service.getLogDetail(999L), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 요청은_있고_응답이_아직_없으면_처리중_상세를_돌려준다() {
        AiRequestLog request = new AiRequestLog(null, "/internal/v1/users/1/context", "{}");
        when(requestRepo.findById(7L)).thenReturn(Optional.of(request));
        when(responseRepo.findFirstByRequestIdOrderByCreatedAtDesc(7L)).thenReturn(Optional.empty());

        AdminAiLogDetailResponse detail = service.getLogDetail(7L);

        assertThat(detail.status()).isEqualTo("PROCESSING");
        assertThat(detail.responseBody()).isNull();
        assertThat(detail.endpoint()).isEqualTo("/internal/v1/users/1/context");
    }

    /** 성공 1건(200)·실패 1건(연결 실패)·처리중 1건이 섞인 목록을 깔아둔다. */
    private void givenMixedLogs() {
        AiRequestLog success = requestLog(1L);
        AiRequestLog failed = requestLog(2L);
        AiRequestLog processing = requestLog(3L);
        when(requestRepo.findAllByOrderByCreatedAtDesc())
                .thenReturn(List.of(success, failed, processing));
        when(responseRepo.findFirstByRequestIdOrderByCreatedAtDesc(1L))
                .thenReturn(Optional.of(new AiResponseLog(1L, 200, 120, "{}")));
        // status_code 가 null = 호출은 끝났는데 응답을 못 받음(연결 실패) → FAILED
        when(responseRepo.findFirstByRequestIdOrderByCreatedAtDesc(2L))
                .thenReturn(Optional.of(new AiResponseLog(2L, null, null, "timeout")));
        when(responseRepo.findFirstByRequestIdOrderByCreatedAtDesc(3L))
                .thenReturn(Optional.empty());
    }

    /** id 는 영속 시 채워지므로 단위 테스트에선 직접 심는다(응답 매칭 키). */
    private AiRequestLog requestLog(long id) {
        AiRequestLog request = new AiRequestLog(null, "/internal/v1/users/1/context", "{}");
        ReflectionTestUtils.setField(request, "id", id);
        return request;
    }

    @Test
    @DisplayName("status=FAILED 면 실패만 걸러 내려준다")
    void filtersFailedOnly() {
        givenMixedLogs();

        assertThat(service.listLogs("FAILED"))
                .extracting(AdminAiLogResponse::id)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("필터가 없거나 ALL 이면 전체 — 파라미터 없이 부르던 기존 호출과 같다")
    void noFilterKeepsEverything() {
        givenMixedLogs();

        assertThat(service.listLogs()).hasSize(3);
        assertThat(service.listLogs(null)).hasSize(3);
        assertThat(service.listLogs("  ")).hasSize(3);
        assertThat(service.listLogs("ALL")).hasSize(3);
    }

    @Test
    @DisplayName("대소문자는 가리지 않는다")
    void filterIsCaseInsensitive() {
        givenMixedLogs();

        assertThat(service.listLogs("success"))
                .extracting(AdminAiLogResponse::id)
                .containsExactly(1L);
    }

    @Test
    @DisplayName("모르는 상태값은 조용히 전체를 주지 않고 VALIDATION_ERROR — 오타를 '실패 없음'으로 오해하면 안 된다")
    void unknownStatusIsRejected() {
        ApiException ex = catchThrowableOfType(
                () -> service.listLogs("ERROR"), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}
