package com.bambi.service.like;

import com.bambi.service.card.Card;
import com.bambi.service.card.CardRepository;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.like.dto.LikeResponse;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LikeService} 검증 — 공개 카드만 좋아요 / 멱등 좋아요.
 */
class LikeServiceTest {

    private final LikeRepository likeRepository = mock(LikeRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final com.bambi.service.user.UserRepository userRepository =
            mock(com.bambi.service.user.UserRepository.class);
    private final com.bambi.service.notification.NotificationService notificationService =
            mock(com.bambi.service.notification.NotificationService.class);
    private final LikeService service =
            new LikeService(likeRepository, cardRepository, userRepository, notificationService);

    @Test
    void 비공개_카드는_좋아요할_수_없다_NOT_FOUND() {
        Card privateCard = mock(Card.class);
        when(privateCard.getVisibility()).thenReturn("PRIVATE");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(privateCard));

        ApiException ex = catchThrowableOfType(
                () -> service.like(1L, UUID.randomUUID().toString()), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        verify(likeRepository, never()).insertIgnore(anyLong(), anyLong());
    }

    @Test
    void 존재하지_않는_카드_좋아요는_NOT_FOUND() {
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.like(1L, UUID.randomUUID().toString()), ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 공개_카드_좋아요는_멱등이고_총수를_돌려준다() {
        Card publicCard = mock(Card.class);
        when(publicCard.getVisibility()).thenReturn("PUBLIC");
        when(publicCard.getId()).thenReturn(10L);
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(publicCard));
        when(likeRepository.countByCardId(10L)).thenReturn(3L);

        LikeResponse res = service.like(1L, UUID.randomUUID().toString());

        assertThat(res.liked()).isTrue();
        assertThat(res.likeCount()).isEqualTo(3L);
        verify(likeRepository).insertIgnore(1L, 10L);
    }

    // ---- 좋아요 알림 (2026-08-11 여진 요청) ------------------------------------

    private Card publicCardOwnedBy(long ownerId, UUID cardPublicId) {
        Card card = mock(Card.class);
        when(card.getVisibility()).thenReturn("PUBLIC");
        when(card.getId()).thenReturn(10L);
        when(card.getUserId()).thenReturn(ownerId);
        when(card.getPublicId()).thenReturn(cardPublicId);
        when(card.getTitle()).thenReturn("반도체 전망");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(card));
        return card;
    }

    @Test
    void 타인_카드_신규_좋아요는_작성자에게_알림을_만든다() {
        UUID cardPublicId = UUID.randomUUID();
        publicCardOwnedBy(2L, cardPublicId);
        when(likeRepository.insertIgnore(1L, 10L)).thenReturn(1);   // 신규 좋아요
        com.bambi.service.user.User liker = mock(com.bambi.service.user.User.class);
        when(liker.getDisplayName()).thenReturn("파라미");
        when(userRepository.findById(1L)).thenReturn(Optional.of(liker));

        service.like(1L, cardPublicId.toString());

        verify(notificationService).notifyLiked(2L, 1L, "파라미", 10L, cardPublicId, "반도체 전망");
    }

    @Test
    void 본인_카드_좋아요는_알림을_만들지_않는다() {
        UUID cardPublicId = UUID.randomUUID();
        publicCardOwnedBy(1L, cardPublicId);                         // 소유자 = 행위자
        when(likeRepository.insertIgnore(1L, 10L)).thenReturn(1);

        service.like(1L, cardPublicId.toString());

        verify(notificationService, never()).notifyLiked(
                anyLong(), anyLong(), any(), anyLong(), any(), any());
    }

    @Test
    void 재좋아요는_알림을_만들지_않는다() {
        // 좋아요↔취소 반복이 알림 스팸이 되면 안 된다 — insertIgnore 0건이면 호출 자체를 안 한다.
        UUID cardPublicId = UUID.randomUUID();
        publicCardOwnedBy(2L, cardPublicId);
        when(likeRepository.insertIgnore(1L, 10L)).thenReturn(0);   // 이미 좋아요 중

        service.like(1L, cardPublicId.toString());

        verify(notificationService, never()).notifyLiked(
                anyLong(), anyLong(), any(), anyLong(), any(), any());
    }
}
