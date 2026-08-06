package com.bambi.service.generation;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import com.bambi.service.generation.dto.GenerationTriggerResponse;
import com.bambi.service.interest.InterestService;
import com.bambi.service.interest.dto.InterestRequest;
import com.bambi.service.wiki.AgentWikiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 사용자가 직접 "지금 생성"을 눌렀을 때, 스케줄러를 기다리지 않고 즉시 리포트 생성을 요청한다.
 * 스케줄러와 같은 {@link GenerationClient} 경계를 재사용하며 대상은 요청한 본인 1명이다.
 *
 * <p>프론트가 topic 을 고르지 않고, 서버가 agent 관심사(위키 태그)를 확인해 <b>대표 관심사 1개</b>를
 * 생성 요청의 topic 으로 넣는다. 계약상 topic 은 표시용 라벨이 아니라 agent 의 <b>실제 검색 주제</b>라
 * (유림 확인 08-05), 고정 문구("내 관심사 종합 브리핑")를 넣으면 그 문구로 검색해 엉뚱한 기사를 물어온다.
 * 종합 자체는 agent 가 사용자 위키 컨텍스트로 하되, 검색 시드만 대표 관심사로 준다.
 * 관심사가 하나도 없으면 생성할 게 없으므로 {@link ErrorCode#VALIDATION_ERROR} 로 거절한다.
 *
 * <p>펜딩 키({@code id})는 service 가 발급한다 — agent 가 202 를 줘도 body 파싱 실패 시 agent 식별자는
 * null 이 될 수 있어 키로 못 쓴다(우석 협의). id 는 멱등키에서 파생(deterministic)이라 같은 분 연타는
 * 같은 id 로 모여, agent 뿐 아니라 펜딩 목록에서도 1건으로 합쳐진다(우석 펜딩 테이블 upsert 키).
 * (후속: id 발급은 펜딩 테이블 도입 시 스케줄러와 공용 접수 레이어로 이동 — 우석 08-05)
 *
 * <p>멱등키는 분 단위라 같은 분 내 연타(더블클릭)는 Job 1개로 합쳐진다. "이미 진행 중인 작업" 완전 차단
 * (GENERATION_IN_PROGRESS)은 생성 작업 상태 영속화가 필요해 후속 과제다(여진 5번 — 펜딩 목록 API와 함께).
 */
@Service
public class OnDemandGenerationService {

    private static final Logger log = LoggerFactory.getLogger(OnDemandGenerationService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GenerationClient generationClient;
    private final AgentWikiClient wikiClient;
    private final GenerationPendingService pendingService;
    private final InterestService interestService;
    private final String contentType;

    public OnDemandGenerationService(
            GenerationClient generationClient,
            AgentWikiClient wikiClient,
            GenerationPendingService pendingService,
            InterestService interestService,
            @Value("${app.scheduler.generation.content-type:interest_news_card}") String contentType) {
        this.generationClient = generationClient;
        this.wikiClient = wikiClient;
        this.pendingService = pendingService;
        this.interestService = interestService;
        this.contentType = contentType;
    }

    /**
     * 즉시 생성 Job 을 접수하고 트리거 응답을 반환한다. 검색 주제는 두 갈래다(2026-08-06 계약):
     * <ul>
     *   <li>{@code requestedTopic} 이 오면(사용자가 홈 rail 에서 선택) 그 이름을 그대로 쓰되,
     *       내 관심사에 없으면 USER 관심사로 원자 추가한다("선택 주제는 관심사에 포함" 기획 요구).
     *       추가는 {@link InterestService#create} 재사용 — 새로 추가될 때만 InterestChangedEvent 가
     *       발행돼 agent 자동 재동기화(#46)를 그대로 탄다. 이미 있으면(중복) 추가 없이 통과.
     *   <li>없으면 기존처럼 대표 관심사(위키 태그 score 최고)를 자동 선택한다(하위호환).
     *       관심사가 하나도 없으면 VALIDATION_ERROR.
     * </ul>
     *
     * <p>응답 키 id 는 service 발급이라 항상 보장, agentJobId 는 agent 식별자(파싱 실패 시 null 가능).
     */
    public GenerationTriggerResponse generateForUser(long userId, String requestedTopic) {
        String topic = resolveTopic(userId, requestedTopic);
        GenerationRequest request = new GenerationRequest(
                onDemandKey(userId), topic, contentType, null, null);
        // agent 202 body 파싱 실패 시 null 일 수 있어 키로 쓰지 않는다 — 참고용으로만 내린다.
        String agentJobId = generationClient.requestGeneration(userId, request);
        // 접수 성공 후 펜딩 영속화(ON_DEMAND) — id 는 멱등키 파생이라 트리거 응답과 같은 값.
        // 기록 실패는 삼켜져 접수 응답을 막지 않는다(register 내부 정책).
        String id = pendingService.register(userId, request.idempotencyKey(),
                GenerationPendingService.REPORT_TYPE_ON_DEMAND, topic, contentType, agentJobId);
        log.info("[OnDemandGeneration] 즉시 생성 요청 userId={}, topic={}, idempotencyKey={}, id={}, agentJobId={}",
                userId, topic, request.idempotencyKey(), id, agentJobId);
        return GenerationTriggerResponse.accepted(id, agentJobId);
    }

    /**
     * 검색 주제 확정 — 선택 topic 이 있으면 관심사 원자 처리 후 그 값을, 없으면 대표 관심사를 쓴다.
     * 프론트는 버튼 비활성화가 1차 가드이고, 서버는 표준 코드로 방어한다
     * (프론트 constants/errors.ts 가 알려진 코드만 매핑 → VALIDATION_ERROR 로 통일, message 는 미노출).
     */
    private String resolveTopic(long userId, String requestedTopic) {
        if (requestedTopic != null && !requestedTopic.isBlank()) {
            String topic = requestedTopic.strip();
            ensureInterest(userId, topic);
            return topic;
        }
        // 대표 관심사(score 최고 태그)를 검색 주제로 쓴다. 없으면 종합할 게 없어 거절한다.
        return wikiClient.getTags(userId).topTopic()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "생성할 관심사가 없습니다."));
    }

    /**
     * 선택 topic 을 내 관심사에 원자 반영한다 — 없으면 USER 직접 입력으로 추가, 이미 있으면(중복) 통과.
     * 관심사 추가 실패(중복 외)는 생성 자체를 막는다 — "선택 주제는 관심사에 포함돼야 한다"가 기획 전제라
     * 조용히 계속 가면 전제가 깨진 채 생성된다.
     */
    private void ensureInterest(long userId, String topic) {
        try {
            interestService.create(userId, new InterestRequest(topic, null, null));
        } catch (ApiException e) {
            if (e.getErrorCode() != ErrorCode.DUPLICATE_RESOURCE) {
                throw e;
            }
            // 이미 내 관심사에 있음 — 추가 없이 통과 (이벤트도 안 나가 불필요한 재동기화 없음)
        }
    }

    /** on-demand 멱등키(분 단위) — 연타는 1건, 시간 지나면 새 생성. */
    private String onDemandKey(long userId) {
        long minute = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toEpochSecond();
        return "ondemand-" + userId + "-" + contentType + "-" + minute;
    }
}
