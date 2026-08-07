package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminAiLogDetailResponse;
import com.bambi.service.admin.dto.AdminAiLogResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 관리자 AI 처리 로그 조회.
 *
 * 요청(ai_request_logs)을 최신순으로 훑으며 각 요청의 최신 응답을 붙여 한 줄로 만든다.
 * 요청 수가 많아지면 N+1 이 되지만, 로그 적재는 아직 시작 전(P1)이라 지금은 데이터가 없다.
 * 실제 트래픽이 붙어 성능이 문제되면 join 쿼리로 바꾼다 — 그때 벤치로 확인하고 옮긴다.
 */
@Service
public class AdminAiLogService {

    /** 파생 상태값(= {@link AiResponseLog#deriveStatus}). 대시보드 집계도 이 상수를 쓴다. */
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PROCESSING = "PROCESSING";

    /** 필터로 "전체"를 뜻하는 값. 화면 탭이 ALL 을 그대로 보내도 받아준다. */
    private static final String STATUS_ALL = "ALL";

    private static final Set<String> SELECTABLE_STATUSES =
            Set.of(STATUS_SUCCESS, STATUS_FAILED, STATUS_PROCESSING);

    private final AiRequestLogRepository requestLogRepository;
    private final AiResponseLogRepository responseLogRepository;

    public AdminAiLogService(AiRequestLogRepository requestLogRepository,
                             AiResponseLogRepository responseLogRepository) {
        this.requestLogRepository = requestLogRepository;
        this.responseLogRepository = responseLogRepository;
    }

    @Transactional(readOnly = true)
    public List<AdminAiLogResponse> listLogs() {
        return listLogs(null);
    }

    /**
     * 처리 상태로 걸러 조회한다. status 가 null·공백·{@code ALL} 이면 전체.
     *
     * <p>상태는 DB 컬럼이 아니라 요청+최신응답에서 파생하는 값이라(§deriveStatus) where 절로
     * 못 내린다. 그래서 훑은 뒤 걸러낸다 — 조회 비용은 전체 조회와 같고, 줄어드는 건 응답 크기다.
     * 그래도 서버에서 거르는 값이 있다: 운영자가 "실패만" 보려고 전체를 받아 브라우저에서
     * 거르지 않아도 되고, 대시보드의 실패 건수에서 바로 이 목록으로 넘어올 수 있다.
     *
     * <p>모르는 값이 오면 조용히 전체를 주지 않고 {@link ErrorCode#VALIDATION_ERROR} 로 거절한다.
     * 오타난 필터가 "전체"로 보이면 운영자가 실패가 없다고 오해한다.
     */
    @Transactional(readOnly = true)
    public List<AdminAiLogResponse> listLogs(String status) {
        String wanted = normalizeStatus(status);
        return requestLogRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(request -> {
                    AiResponseLog response = responseLogRepository
                            .findFirstByRequestIdOrderByCreatedAtDesc(request.getId())
                            .orElse(null);
                    return AdminAiLogResponse.of(request, response);
                })
                .filter(log -> wanted == null || wanted.equals(log.status()))
                .toList();
    }

    /** 필터 값 정규화. 전체를 뜻하면 null, 아는 상태면 대문자 표준값, 그 외는 거절. */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.strip().toUpperCase(Locale.ROOT);
        if (STATUS_ALL.equals(value)) {
            return null;
        }
        if (!SELECTABLE_STATUSES.contains(value)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "알 수 없는 상태 필터입니다: " + status);
        }
        return value;
    }

    /**
     * AI 로그 한 건의 상세(요청·응답 본문 포함)를 조회한다.
     * 요청 로그가 없으면 NOT_FOUND. 응답이 아직 없으면 처리 중이라 본문은 요청만 채워진다.
     */
    @Transactional(readOnly = true)
    public AdminAiLogDetailResponse getLogDetail(long requestId) {
        AiRequestLog request = requestLogRepository.findById(requestId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "AI 로그를 찾을 수 없습니다 (id=" + requestId + ")"));
        AiResponseLog response = responseLogRepository
                .findFirstByRequestIdOrderByCreatedAtDesc(requestId)
                .orElse(null);
        return AdminAiLogDetailResponse.of(request, response);
    }
}
