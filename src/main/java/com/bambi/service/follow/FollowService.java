package com.bambi.service.follow;

import com.bambi.service.card.CardRepository;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.follow.dto.FollowResponse;
import com.bambi.service.follow.dto.FollowUserResponse;
import com.bambi.service.follow.dto.ProfileResponse;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 팔로우 도메인 (SNS/Week2). 대상 사용자는 대외 식별자 publicId(UUID)로만 가리킨다.
 * 팔로우/언팔은 멱등(ON CONFLICT DO NOTHING / 없어도 무시) — 낙관적 업데이트 재시도에 안전.
 * 자기 자신 팔로우는 서비스에서 400 으로 막고, DB CHECK(V4)가 이중 방어한다.
 */
@Service
public class FollowService {

    private static final String PUBLIC = "PUBLIC";

    private final FollowRepository followRepository;
    private final UserRepository userRepository;
    private final CardRepository cardRepository;

    public FollowService(FollowRepository followRepository,
                         UserRepository userRepository,
                         CardRepository cardRepository) {
        this.followRepository = followRepository;
        this.userRepository = userRepository;
        this.cardRepository = cardRepository;
    }

    @Transactional
    public FollowResponse follow(Long followerId, String followeePublicId) {
        Long followeeId = resolveUserId(followeePublicId);
        if (followeeId.equals(followerId)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "자기 자신은 팔로우할 수 없습니다.");
        }
        followRepository.insertIgnore(followerId, followeeId);   // 멱등
        return new FollowResponse(true, followRepository.countByFolloweeId(followeeId));
    }

    @Transactional
    public FollowResponse unfollow(Long followerId, String followeePublicId) {
        Long followeeId = resolveUserId(followeePublicId);
        followRepository.deleteRelation(followerId, followeeId);  // 없어도 멱등
        return new FollowResponse(false, followRepository.countByFolloweeId(followeeId));
    }

    /**
     * 공개 프로필 + 팔로우 통계. following = 조회자(viewerId)가 이 사용자를 팔로우 중인지.
     * viewerId=null(비로그인 게스트)이면 following 은 항상 false(조회 없이).
     */
    @Transactional(readOnly = true)
    public ProfileResponse profile(Long viewerId, String targetPublicId) {
        User target = resolveUser(targetPublicId);
        Long targetId = target.getId();
        boolean following = viewerId != null
                && !targetId.equals(viewerId)
                && followRepository.existsByFollowerIdAndFolloweeId(viewerId, targetId);
        // 프로필 통계(여진 목업): 최근 공개 시각(근사)·이번 주(롤링 7일) 공개 개수.
        OffsetDateTime lastPublishedAt = cardRepository.findLastPublishedAt(targetId);
        long weekPublicCount = cardRepository
                .countByUserIdAndVisibilityAndDeletedAtIsNullAndCreatedAtAfter(
                        targetId, PUBLIC, OffsetDateTime.now().minusDays(7));
        return new ProfileResponse(
                target.getPublicId(),
                target.getUsername(),
                target.getDisplayName(),
                target.getBio(),
                target.getCreatedAt(),
                followRepository.countByFolloweeId(targetId),
                followRepository.countByFollowerId(targetId),
                following,
                cardRepository.countByUserIdAndVisibilityAndDeletedAtIsNull(targetId, PUBLIC),
                lastPublishedAt,
                weekPublicCount);
    }

    /**
     * 팔로워 목록(이 사용자를 팔로우하는 사람들). 공개 프로필처럼 게스트 열람 허용.
     * @param viewerId 조회자 id. 비로그인이면 null — 각 항목의 following 은 전부 false.
     */
    @Transactional(readOnly = true)
    public List<FollowUserResponse> followers(Long viewerId, String targetPublicId) {
        User target = resolveUser(targetPublicId);
        return toUserList(viewerId, followRepository.findFollowerIds(target.getId()));
    }

    /** 팔로잉 목록(이 사용자가 팔로우하는 사람들). 게스트 열람 허용, following 은 뷰어 기준. */
    @Transactional(readOnly = true)
    public List<FollowUserResponse> following(Long viewerId, String targetPublicId) {
        User target = resolveUser(targetPublicId);
        return toUserList(viewerId, followRepository.findFolloweeIds(target.getId()));
    }

    /**
     * id 목록 → 사용자 응답. 탈퇴 계정은 제외하고, viewer 가 각 사용자를 팔로우 중인지(following)를
     * 1 IN 쿼리로 배치 판단한다(N+1 회피). 게스트(viewerId=null)면 following 은 전부 false(조회 없이).
     * 정렬은 username 오름차순(결정적 순서).
     */
    private List<FollowUserResponse> toUserList(Long viewerId, List<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        Set<Long> viewerFollowing = viewerId == null ? Set.of()
                : new HashSet<>(followRepository.findFollowedAmong(viewerId, userIds));
        return userRepository.findByIdInAndDeletedAtIsNull(userIds).stream()
                .sorted(Comparator.comparing(u -> u.getUsername() == null ? "" : u.getUsername(),
                        String.CASE_INSENSITIVE_ORDER))
                .map(u -> new FollowUserResponse(
                        u.getPublicId(), u.getUsername(), u.getDisplayName(),
                        viewerFollowing.contains(u.getId())))
                .toList();
    }

    private Long resolveUserId(String publicId) {
        return resolveUser(publicId).getId();
    }

    /** publicId 로 살아있는 사용자 조회. 형식 오류/없음은 존재 노출 없이 404. */
    private User resolveUser(String publicId) {
        UUID uuid;
        try {
            uuid = UUID.fromString(publicId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다.");
        }
        return userRepository.findByPublicIdAndDeletedAtIsNull(uuid)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }
}
