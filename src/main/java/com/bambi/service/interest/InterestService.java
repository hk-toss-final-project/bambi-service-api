package com.bambi.service.interest;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.interest.dto.InterestRequest;
import com.bambi.service.interest.dto.InterestResponse;
import com.bambi.service.interest.taxonomy.InterestTaxonomyService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 관심사 CRUD (P0) — 소유자 범위 + soft delete + 이름 중복 방지.
 * note 템플릿과 같은 구조(Controller→Service→Repository, 권한은 userId 로 강제).
 * 이 API 로 만드는 관심사는 항상 source=USER(직접 입력). INFERRED 는 agent 몫(P1).
 *
 * <p>관심사가 바뀌면 {@link InterestChangedEvent} 를 발행해 커밋 후 agent 컨텍스트를 재동기화한다.
 * 프론트가 {@code POST /api/interests/sync} 를 빠뜨려도 반영되도록 하는 안전망이다.
 */
@Service
public class InterestService {

    private final InterestRepository interestRepository;
    private final InterestTaxonomyService taxonomyService;
    private final ApplicationEventPublisher eventPublisher;

    public InterestService(
            InterestRepository interestRepository,
            InterestTaxonomyService taxonomyService,
            ApplicationEventPublisher eventPublisher) {
        this.interestRepository = interestRepository;
        this.taxonomyService = taxonomyService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public InterestResponse create(Long userId, InterestRequest req) {
        var selection = resolveSelection(req);
        String name = selection.name();
        if (interestRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, name)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "이미 등록한 관심사입니다.");
        }
        Interest interest = selection.taxonomy() == null
                ? new Interest(userId, name)
                : Interest.fromTaxonomy(
                        userId,
                        name,
                        selection.taxonomy().taxonomyVersion(),
                        selection.taxonomy().categoryId(),
                        selection.taxonomy().topicId());
        interestRepository.save(interest);
        eventPublisher.publishEvent(new InterestChangedEvent(userId));
        return InterestResponse.from(interest);
    }

    @Transactional(readOnly = true)
    public List<InterestResponse> list(Long userId) {
        return interestRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(InterestResponse::from)
                .toList();
    }

    /**
     * 이름으로 내 관심사 존재 여부를 확인한다(soft delete 제외).
     * 온디맨드 생성이 "선택 주제가 내 관심사에 있는지" 검증하는 데 쓴다 — 이름 기준인 이유는
     * 온보딩 관심사(service id)와 LLM Wiki 태그(agent id)의 ID 체계가 달라서다(2026-08-06 계약).
     */
    @Transactional(readOnly = true)
    public boolean existsByName(Long userId, String name) {
        return name != null
                && interestRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, name.strip());
    }

    @Transactional
    public InterestResponse rename(Long userId, Long interestId, InterestRequest req) {
        var selection = resolveSelection(req);
        String name = selection.name();
        Interest interest = findOwned(userId, interestId);
        // 이름이 실제로 바뀔 때만 중복 검사 (자기 자신과의 충돌 제외)
        if (!interest.getName().equals(name)
                && interestRepository.existsByUserIdAndNameAndDeletedAtIsNull(userId, name)) {
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "이미 등록한 관심사입니다.");
        }
        if (selection.taxonomy() == null) {
            interest.rename(name);
        } else {
            interest.selectTaxonomyTopic(
                    name,
                    selection.taxonomy().taxonomyVersion(),
                    selection.taxonomy().categoryId(),
                    selection.taxonomy().topicId());
        }
        eventPublisher.publishEvent(new InterestChangedEvent(userId));
        return InterestResponse.from(interest);
    }

    @Transactional
    public void delete(Long userId, Long interestId) {
        Interest interest = findOwned(userId, interestId);
        interest.softDelete();
        eventPublisher.publishEvent(new InterestChangedEvent(userId));
    }

    /** 내 것(soft delete 제외)만 조회. 없으면 NOT_FOUND — 남의 것도 존재 노출 없이 404. */
    private Interest findOwned(Long userId, Long interestId) {
        return interestRepository.findByIdAndUserIdAndDeletedAtIsNull(interestId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "관심사를 찾을 수 없습니다."));
    }

    /** 요청을 canonical taxonomy 토픽 또는 직접 입력 문자열로 정규화한다. */
    private ResolvedSelection resolveSelection(InterestRequest request) {
        if (!request.isTaxonomySelection()) {
            return new ResolvedSelection(request.name().strip(), null);
        }
        var topic = taxonomyService.resolveActiveTopic(request.taxonomyVersion(), request.topicId());
        return new ResolvedSelection(topic.topicName(), topic);
    }

    /** 관심사 저장에 필요한 정규화 결과. */
    private record ResolvedSelection(
            String name,
            InterestTaxonomyService.ResolvedTopic taxonomy) {
    }
}
