package com.bambi.service.follow;

import com.bambi.service.card.CardRepository;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.follow.dto.FollowResponse;
import com.bambi.service.follow.dto.FollowUserResponse;
import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FollowService} 검증 — 자기팔로우 차단 / 멱등 팔로우 / 언팔.
 */
class FollowServiceTest {

    private final FollowRepository followRepository = mock(FollowRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final com.bambi.service.notification.NotificationService notificationService =
            mock(com.bambi.service.notification.NotificationService.class);
    private final FollowService service =
            new FollowService(followRepository, userRepository, cardRepository, notificationService);

    @Test
    void 자기_자신은_팔로우할_수_없다() {
        User me = mock(User.class);
        when(me.getId()).thenReturn(1L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(me));

        ApiException ex = catchThrowableOfType(
                () -> service.follow(1L, UUID.randomUUID().toString()), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        verify(followRepository, never()).insertIgnore(anyLong(), anyLong());   // 저장 시도조차 없어야
    }

    @Test
    void 없는_사용자를_팔로우하면_NOT_FOUND() {
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.follow(1L, UUID.randomUUID().toString()), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 팔로우는_멱등이고_팔로워수를_돌려준다() {
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));
        when(followRepository.countByFolloweeId(2L)).thenReturn(5L);

        FollowResponse res = service.follow(1L, UUID.randomUUID().toString());

        assertThat(res.following()).isTrue();
        assertThat(res.followerCount()).isEqualTo(5L);
        verify(followRepository).insertIgnore(1L, 2L);   // ON CONFLICT DO NOTHING (중복이어도 예외 없음)
    }

    // ---- 팔로우 알림 (2026-08-11 여진 요청) ------------------------------------

    @Test
    void 첫_팔로우면_대상에게_알림을_만든다() {
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));
        when(followRepository.insertIgnore(1L, 2L)).thenReturn(1);   // 새 관계
        User follower = mock(User.class);
        when(follower.getDisplayName()).thenReturn("파라미");
        UUID followerPublicId = UUID.randomUUID();
        when(follower.getPublicId()).thenReturn(followerPublicId);
        when(userRepository.findById(1L)).thenReturn(Optional.of(follower));

        service.follow(1L, UUID.randomUUID().toString());

        verify(notificationService).notifyFollowed(2L, 1L, "파라미", followerPublicId);
    }

    @Test
    void 재팔로우는_알림을_만들지_않는다() {
        // 팔로우↔언팔 반복이 알림 스팸이 되면 안 된다 — insertIgnore 0건이면 호출 자체를 안 한다.
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));
        when(followRepository.insertIgnore(1L, 2L)).thenReturn(0);   // 이미 팔로우 중

        service.follow(1L, UUID.randomUUID().toString());

        verify(notificationService, never()).notifyFollowed(anyLong(), anyLong(), any(), any());
    }

    @Test
    void 알림_생성이_실패해도_팔로우는_정상_처리된다() {
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));
        when(followRepository.insertIgnore(1L, 2L)).thenReturn(1);
        when(userRepository.findById(1L)).thenThrow(new RuntimeException("db down"));
        when(followRepository.countByFolloweeId(2L)).thenReturn(3L);

        FollowResponse res = service.follow(1L, UUID.randomUUID().toString());   // 예외 없이

        assertThat(res.following()).isTrue();
        assertThat(res.followerCount()).isEqualTo(3L);
    }

    @Test
    void 언팔은_없어도_멱등이다() {
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));
        when(followRepository.countByFolloweeId(2L)).thenReturn(0L);

        FollowResponse res = service.unfollow(1L, UUID.randomUUID().toString());

        assertThat(res.following()).isFalse();
        assertThat(res.followerCount()).isEqualTo(0L);
        verify(followRepository).deleteRelation(1L, 2L);
    }

    @Test
    void 프로필에는_bio_와_가입시점이_들어간다() {
        java.time.OffsetDateTime joined = java.time.OffsetDateTime.parse("2026-03-01T00:00:00+09:00");
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(target.getBio()).thenReturn("매일 아침 브리핑");
        when(target.getCreatedAt()).thenReturn(joined);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));

        var profile = service.profile(null, UUID.randomUUID().toString());   // 게스트 열람

        assertThat(profile.bio()).isEqualTo("매일 아침 브리핑");
        assertThat(profile.joinedAt()).isEqualTo(joined);
        assertThat(profile.following()).isFalse();   // 게스트는 팔로우 여부 조회 없이 false
    }

    @Test
    void 프로필_통계는_최근_공개시각과_이번주_공개수를_채운다() {
        java.time.OffsetDateTime last = java.time.OffsetDateTime.parse("2026-08-06T09:00:00+09:00");
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(target));
        when(cardRepository.findLastPublishedAt(2L)).thenReturn(last);
        when(cardRepository.countByUserIdAndVisibilityAndDeletedAtIsNullAndCreatedAtAfter(
                eq(2L), eq("PUBLIC"), any())).thenReturn(3L);

        var profile = service.profile(null, UUID.randomUUID().toString());

        assertThat(profile.lastPublishedAt()).isEqualTo(last);
        assertThat(profile.weekPublicCount()).isEqualTo(3L);
    }

    @Test
    void 팔로워_목록은_username순으로_following플래그를_뷰어기준으로_채운다() {
        UUID targetPublicId = UUID.randomUUID();
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(targetPublicId)).thenReturn(Optional.of(target));
        when(followRepository.findFollowerIds(2L)).thenReturn(List.of(10L, 11L));

        User bob = mock(User.class);       // id 11, username "bob"
        when(bob.getId()).thenReturn(11L);
        when(bob.getUsername()).thenReturn("bob");
        when(bob.getPublicId()).thenReturn(UUID.randomUUID());
        User alice = mock(User.class);     // id 10, username "alice"
        when(alice.getId()).thenReturn(10L);
        when(alice.getUsername()).thenReturn("alice");
        when(alice.getPublicId()).thenReturn(UUID.randomUUID());
        // 저장 순서와 무관하게 서비스가 username 오름차순으로 정렬해야 한다
        when(userRepository.findByIdInAndDeletedAtIsNull(List.of(10L, 11L))).thenReturn(List.of(bob, alice));
        // 뷰어(1L)는 alice(10L)만 팔로우 중
        when(followRepository.findFollowedAmong(1L, List.of(10L, 11L))).thenReturn(List.of(10L));

        List<FollowUserResponse> list = service.followers(1L, targetPublicId.toString());

        assertThat(list).extracting(FollowUserResponse::username).containsExactly("alice", "bob");
        assertThat(list.get(0).following()).isTrue();    // alice
        assertThat(list.get(1).following()).isFalse();   // bob
    }

    @Test
    void 팔로잉_목록_게스트는_following_조회없이_전부_false() {
        UUID targetPublicId = UUID.randomUUID();
        User target = mock(User.class);
        when(target.getId()).thenReturn(2L);
        when(userRepository.findByPublicIdAndDeletedAtIsNull(targetPublicId)).thenReturn(Optional.of(target));
        when(followRepository.findFolloweeIds(2L)).thenReturn(List.of(10L));
        User alice = mock(User.class);
        when(alice.getId()).thenReturn(10L);
        when(alice.getUsername()).thenReturn("alice");
        when(alice.getPublicId()).thenReturn(UUID.randomUUID());
        when(userRepository.findByIdInAndDeletedAtIsNull(List.of(10L))).thenReturn(List.of(alice));

        List<FollowUserResponse> list = service.following(null, targetPublicId.toString());

        assertThat(list).hasSize(1);
        assertThat(list.get(0).following()).isFalse();
        verify(followRepository, never()).findFollowedAmong(any(), anyCollection());
    }
}
