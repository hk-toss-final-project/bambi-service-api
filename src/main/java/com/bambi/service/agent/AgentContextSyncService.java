package com.bambi.service.agent;

import com.bambi.service.agent.dto.AgentContextRequest;
import com.bambi.service.agent.dto.AgentInterestTaxonomyRequest;
import com.bambi.service.agent.dto.AgentSignupInterest;
import com.bambi.service.interest.Interest;
import com.bambi.service.interest.InterestRepository;
import com.bambi.service.interest.InterestSource;
import com.bambi.service.interest.taxonomy.InterestTaxonomyService;
import com.bambi.service.interest.taxonomy.dto.InterestTaxonomyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 사용자 컨텍스트를 agent 에 반영한다(계약 §4).
 *
 * <p>agent 는 {@code context_version} 이 사용자별 단조 증가일 것을 요구하고, 같거나 작은 값을
 * 재전송하면 STALE_CONTEXT_VERSION 으로 거부한다(§4.3). 그래서 보낼 때마다 service-db 의
 * {@code users.agent_context_version} 을 +1 해 저장한 뒤 그 값을 싣는다.
 * 가입뿐 아니라 이후 설정 변경(플랜·개인화·차단) 재동기도 이 진입점을 쓰면 된다.
 */
@Service
public class AgentContextSyncService {

    private static final Logger log = LoggerFactory.getLogger(AgentContextSyncService.class);

    private final InterestRepository interestRepository;
    private final InterestTaxonomyService taxonomyService;
    private final AgentContextVersionAllocator versionAllocator;
    private final AgentGateway agentGateway;

    public AgentContextSyncService(
            InterestRepository interestRepository,
            InterestTaxonomyService taxonomyService,
            AgentContextVersionAllocator versionAllocator,
            AgentGateway agentGateway) {
        this.interestRepository = interestRepository;
        this.taxonomyService = taxonomyService;
        this.versionAllocator = versionAllocator;
        this.agentGateway = agentGateway;
    }

    /**
     * 사용자 컨텍스트를 agent 에 동기화한다. 버전을 +1 해 저장한 뒤(짧은 트랜잭션) 그 버전으로 PUT 한다.
     * HTTP 는 트랜잭션 밖에서 일어나며, 전송이 실패해도 버전은 이미 올라가 있어(단조 증가 유지)
     * 다음 재시도가 더 큰 버전으로 반영한다.
     */
    public void syncUserContext(long userId) {
        InterestTaxonomyResponse taxonomy = taxonomyService.getActiveTaxonomy();
        agentGateway.syncInterestTaxonomy(AgentInterestTaxonomyRequest.from(taxonomy));
        List<Interest> interests = interestRepository
                .findByUserIdAndSourceAndDeletedAtIsNullOrderByNameAsc(userId, InterestSource.USER);
        ContextInterests contextInterests = buildContextInterests(taxonomy, interests);
        int version = versionAllocator.allocate(userId);
        try {
            agentGateway.syncUserContext(userId, toRequest(version, contextInterests));
        } catch (StaleContextVersionException e) {
            // service 로컬 카운터가 agent 실제 버전보다 낮아 거절됨 → agent 가 준 현재 버전에 맞춰 1회 재전송.
            // (예전엔 여기서 조용히 넘어가 관심사가 영영 반영 안 되던 버그 — 유림 08-06)
            reconcileAndResend(userId, e.currentVersion(), contextInterests);
        }
    }

    /** agent 현재 버전에 맞춰 1회 재전송한다. 재전송도 STALE 이면 재경합이므로 다음 동기화에 위임한다. */
    private void reconcileAndResend(long userId, int agentCurrentVersion, ContextInterests contextInterests) {
        int reconciled = versionAllocator.reconcile(userId, agentCurrentVersion);
        log.info("[AgentContextSync] 버전 정합 재전송 (userId={}, agentCurrent={}, 재전송버전={})",
                userId, agentCurrentVersion, reconciled);
        try {
            agentGateway.syncUserContext(userId, toRequest(reconciled, contextInterests));
        } catch (StaleContextVersionException retry) {
            // 재전송도 STALE = 그 사이 다른 동기화가 버전을 또 올린 재경합. 실패가 아니므로 500 으로 터뜨리지 않고,
            // reconcile 로 카운터는 이미 전진했으니 다음 관심사 변경/동기화가 최신 버전으로 자연 재시도한다.
            log.warn("[AgentContextSync] 재전송도 STALE(userId={}, agentCurrent={}) — 재경합, 다음 동기화에 위임",
                    userId, retry.currentVersion());
        }
    }

    private AgentContextRequest toRequest(int version, ContextInterests contextInterests) {
        return AgentContextRequest.forVersion(
                version,
                contextInterests.taxonomyVersion(),
                contextInterests.categoryIds(),
                contextInterests.topicIds(),
                contextInterests.signupInterests());
    }

    /** taxonomy 선택을 Category 묶음으로, 직접 입력 토픽을 Category 없는 묶음으로 만든다. */
    private ContextInterests buildContextInterests(
            InterestTaxonomyResponse taxonomy,
            List<Interest> interests) {
        Map<String, String> categoryNames = taxonomy.categories().stream()
                .collect(java.util.stream.Collectors.toMap(
                        InterestTaxonomyResponse.Category::id,
                        InterestTaxonomyResponse.Category::name));
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        LinkedHashSet<String> categoryIds = new LinkedHashSet<>();
        List<String> topicIds = new ArrayList<>();
        List<String> customTopics = new ArrayList<>();

        for (Interest interest : interests) {
            boolean activeTaxonomyTopic = taxonomy.version().equals(interest.getTaxonomyVersion())
                    && interest.getTaxonomyCategoryId() != null
                    && interest.getTaxonomyTopicId() != null
                    && categoryNames.containsKey(interest.getTaxonomyCategoryId());
            if (!activeTaxonomyTopic) {
                customTopics.add(interest.getName());
                continue;
            }
            categoryIds.add(interest.getTaxonomyCategoryId());
            topicIds.add(interest.getTaxonomyTopicId());
            grouped.computeIfAbsent(interest.getTaxonomyCategoryId(), ignored -> new ArrayList<>())
                    .add(interest.getName());
        }

        List<AgentSignupInterest> signupInterests = new ArrayList<>();
        grouped.forEach((categoryId, topics) -> signupInterests.add(
                new AgentSignupInterest(categoryNames.get(categoryId), topics)));
        if (!customTopics.isEmpty()) {
            signupInterests.add(new AgentSignupInterest(null, customTopics));
        }
        return new ContextInterests(
                topicIds.isEmpty() ? null : taxonomy.version(),
                List.copyOf(categoryIds),
                List.copyOf(topicIds),
                List.copyOf(signupInterests));
    }

    /** Agent Context 요청에 들어갈 관심사 선택 묶음. */
    private record ContextInterests(
            String taxonomyVersion,
            List<String> categoryIds,
            List<String> topicIds,
            List<AgentSignupInterest> signupInterests) {
    }
}
