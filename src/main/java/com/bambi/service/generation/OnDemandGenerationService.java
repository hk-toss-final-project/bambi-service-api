package com.bambi.service.generation;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.generation.dto.GenerationRequest;
import com.bambi.service.generation.dto.GenerationTriggerResponse;
import com.bambi.service.interest.InterestService;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
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
    private final UserRepository userRepository;
    private final String contentType;

    public OnDemandGenerationService(
            GenerationClient generationClient,
            AgentWikiClient wikiClient,
            GenerationPendingService pendingService,
            InterestService interestService,
            UserRepository userRepository,
            @Value("${app.scheduler.generation.content-type:interest_news_card}") String contentType) {
        this.generationClient = generationClient;
        this.wikiClient = wikiClient;
        this.pendingService = pendingService;
        this.interestService = interestService;
        this.userRepository = userRepository;
        this.contentType = contentType;
    }

    /**
     * 즉시 생성 Job 을 접수하고 트리거 응답을 반환한다. 검색 주제는 두 갈래다(2026-08-06 최종 계약):
     * <ul>
     *   <li>{@code requestedTopic} 이 오면(사용자가 홈 rail 에서 선택) <b>내 관심사에 있는 이름만</b>
     *       허용한다. 없으면 자동 추가하지 않고 {@link ErrorCode#INTEREST_NOT_FOUND}(404) 로 거절한다 —
     *       생성 버튼이 관심사를 무의식적으로 늘리지 않게 하는 정책(여진 확정 15:51). Wiki 태그로
     *       생성하려면 프론트가 {@code POST /api/interests} 로 명시적으로 추가한 뒤 다시 요청한다.
     *   <li>없으면 기존처럼 대표 관심사(위키 태그 score 최고)를 자동 선택한다(하위호환).
     *       관심사가 하나도 없으면 VALIDATION_ERROR.
     * </ul>
     *
     * <p>응답 키 id 는 service 발급이라 항상 보장, agentJobId 는 agent 식별자(파싱 실패 시 null 가능).
     */
    public GenerationTriggerResponse generateForUser(long userId, String requestedTopic) {
        String topic = resolveTopic(userId, requestedTopic);
        // 변경점(Delta) 추적은 요청 단위 토글이 아니라 **계정 설정**이다(2026-08-10 김기용 요청,
        // users.change_history_enabled V22). 어떤 주제를 요청하든 저장된 설정값을 그대로 싣는다 —
        // 처음 요청하는 주제는 비교할 과거가 없어 "전부 신규"로 나오고 두 번째부터 전/후 비교가 된다
        // (토픽별 과거 누적 구조라 계정 단위로 켜도 데이터가 안 꼬인다 — 기용 확인).
        //
        // 아침 브리핑에는 여전히 싣지 않는다 — Delta 와 topics[] 둘 다 "기존 생성 경로 대체"라
        // 동시 사용 시 어느 쪽이 이기는지 계약에 정의가 없다(agent-api #12 / #20).
        boolean changeHistory = accountChangeHistoryEnabled(userId);
        // report_type 을 요청에 실어 agent 가 발행 snapshot 에 에코하게 한다(2026-08-06 계약).
        // 온디맨드는 아직 단일 주제다 — topic 이 실제 검색어다(고정 문구를 넣으면 안 된다).
        // 상위 3개 자동 선정 + 연결 분석 전환은 agent 통합 서술이 나온 뒤 별도 작업.
        GenerationRequest request = GenerationRequest.singleTopic(
                onDemandKey(userId, changeHistory), topic, contentType,
                GenerationPendingService.REPORT_TYPE_ON_DEMAND, changeHistory);
        // agent 202 body 파싱 실패 시 null 일 수 있어 키로 쓰지 않는다 — 참고용으로만 내린다.
        String agentJobId = generationClient.requestGeneration(userId, request);
        // 접수 성공 후 펜딩 영속화(ON_DEMAND) — id 는 멱등키 파생이라 트리거 응답과 같은 값.
        // 기록 실패는 삼켜져 접수 응답을 막지 않는다(register 내부 정책).
        String id = pendingService.register(userId, request.idempotencyKey(),
                GenerationPendingService.REPORT_TYPE_ON_DEMAND, topic, contentType, agentJobId);
        log.info("[OnDemandGeneration] 즉시 생성 요청 userId={}, topic={}, delta={}, idempotencyKey={}, id={}, agentJobId={}",
                userId, topic, changeHistory, request.idempotencyKey(), id, agentJobId);
        return GenerationTriggerResponse.accepted(id, agentJobId);
    }

    /**
     * 계정의 Delta 설정 조회 — 탈퇴/미존재 사용자는 꺼짐으로 다룬다(생성 요청을 막을 이유는
     * 인증 계층이 이미 처리했고, 여기서는 비용 큰 경로를 임의로 켜지 않는 쪽이 안전하다).
     */
    private boolean accountChangeHistoryEnabled(long userId) {
        return userRepository.findById(userId).map(User::isChangeHistoryEnabled).orElse(false);
    }

    /**
     * 검색 주제 확정 — 선택 topic 이 있으면 내 관심사 존재를 검증하고, 없으면 대표 관심사를 쓴다.
     * 프론트는 버튼 비활성화·목록 제한이 1차 가드이고, 서버는 표준 코드로 방어한다
     * (프론트 constants/errors.ts 가 알려진 코드만 매핑 → 7종 표준 코드만 쓴다).
     */
    private String resolveTopic(long userId, String requestedTopic) {
        if (requestedTopic != null && !requestedTopic.isBlank()) {
            String topic = requestedTopic.strip();
            // 내 관심사에 없는 주제는 자동 추가하지 않고 거절한다 — 생성이 관심사를 무의식적으로
            // 늘리지 않게 하는 정책(여진 확정 15:51). 프론트는 404 를 받으면 선택을 초기화하고
            // /api/interests 를 재조회한 뒤, Wiki 태그는 명시적으로 추가한 다음 다시 요청한다.
            if (!interestService.existsByName(userId, topic)) {
                throw new ApiException(ErrorCode.INTEREST_NOT_FOUND, "내 관심사에 없는 주제입니다.");
            }
            return topic;
        }
        // 대표 관심사(score 최고 태그)를 검색 주제로 쓴다. 없으면 종합할 게 없어 거절한다.
        return wikiClient.getTags(userId).topTopic()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_ERROR, "생성할 관심사가 없습니다."));
    }

    /**
     * on-demand 멱등키(분 단위) — 연타는 1건, 시간 지나면 새 생성.
     *
     * <p>Delta 여부를 키에 섞는다. 안 섞으면 같은 분 안에서 토글해도 <b>멱등키가 같아
     * agent 가 먼저 접수한 Job 을 그대로 돌려주고</b>, 사용자는 "켰는데 안 바뀐다"를 겪는다.
     * 꺼진 경우에는 접미사를 붙이지 않아 <b>기존 키 형식이 그대로 유지</b>된다(회귀 없음).
     */
    private String onDemandKey(long userId, boolean changeHistory) {
        long minute = OffsetDateTime.now(KST).truncatedTo(ChronoUnit.MINUTES).toEpochSecond();
        String base = "ondemand-" + userId + "-" + contentType + "-" + minute;
        return changeHistory ? base + "-delta" : base;
    }
}
