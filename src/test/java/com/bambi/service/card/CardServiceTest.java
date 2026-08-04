package com.bambi.service.card;

import com.bambi.service.card.dto.CardResponse;
import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CardService} 검증 — 단건 조회 권한(내 것 or PUBLIC, 게스트 포함) / 공개설정 변경 / 도메인 방어.
 */
class CardServiceTest {

    private final CardRepository cardRepository = mock(CardRepository.class);
    private final com.bambi.service.report.ReportRepository reportRepository =
            mock(com.bambi.service.report.ReportRepository.class);
    private final com.bambi.service.user.UserRepository userRepository =
            mock(com.bambi.service.user.UserRepository.class);
    private final com.bambi.service.like.LikeRepository likeRepository =
            mock(com.bambi.service.like.LikeRepository.class);
    private final CardService service =
            new CardService(cardRepository, reportRepository, userRepository, likeRepository);

    private static Card card(long ownerId, String visibility) {
        Card card = mock(Card.class);
        when(card.getId()).thenReturn(100L);
        when(card.getUserId()).thenReturn(ownerId);
        when(card.getVisibility()).thenReturn(visibility);
        when(card.getPublicId()).thenReturn(UUID.randomUUID());
        when(card.getSources()).thenReturn(java.util.List.of());
        when(card.getReportId()).thenReturn(null);
        return card;
    }

    @Test
    void 내_카드는_비공개여도_조회된다() {
        Card mine = card(1L, "PRIVATE");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(mine));

        CardResponse res = service.get(1L, UUID.randomUUID().toString());

        assertThat(res).isNotNull();
    }

    @Test
    void 남의_PUBLIC_카드는_조회된다() {
        Card others = card(2L, "PUBLIC");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(others));

        CardResponse res = service.get(1L, UUID.randomUUID().toString());   // viewer=1, owner=2

        assertThat(res).isNotNull();
    }

    @Test
    void 남의_비공개_카드는_존재_노출_없이_404() {
        Card othersPrivate = card(2L, "PRIVATE");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(othersPrivate));

        ApiException ex = catchThrowableOfType(
                () -> service.get(1L, UUID.randomUUID().toString()), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 게스트는_PUBLIC_카드는_보고_비공개는_404() {
        Card pub = card(2L, "PUBLIC");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(pub));
        assertThat(service.get(null, UUID.randomUUID().toString())).isNotNull();   // 게스트 PUBLIC OK

        Card priv = card(2L, "PRIVATE");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(priv));
        ApiException ex = catchThrowableOfType(
                () -> service.get(null, UUID.randomUUID().toString()), ApiException.class);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);   // 게스트 비공개 404
    }

    @Test
    void 형식_잘못된_publicId_는_404() {
        ApiException ex = catchThrowableOfType(
                () -> service.get(1L, "not-a-uuid"), ApiException.class);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 단건_상세는_소셜_필드를_채운다_로그인_뷰어() {
        Card others = card(2L, "PUBLIC");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(others));
        when(likeRepository.countByCardId(100L)).thenReturn(3L);
        when(likeRepository.existsByUserIdAndCardId(1L, 100L)).thenReturn(true);

        CardResponse res = service.get(1L, UUID.randomUUID().toString());

        assertThat(res.visibility()).isEqualTo("PUBLIC");
        assertThat(res.likeCount()).isEqualTo(3L);
        assertThat(res.liked()).isTrue();
        assertThat(res.author()).isNotNull();   // 작성자 미조회(null user)여도 null 필드로 감싼 객체 반환
    }

    @Test
    void 게스트_단건_상세는_liked_false_다() {
        Card pub = card(2L, "PUBLIC");
        when(cardRepository.findByPublicIdAndDeletedAtIsNull(any())).thenReturn(Optional.of(pub));
        when(likeRepository.countByCardId(100L)).thenReturn(7L);

        CardResponse res = service.get(null, UUID.randomUUID().toString());

        assertThat(res.likeCount()).isEqualTo(7L);
        assertThat(res.liked()).isFalse();   // 게스트는 좋아요 조회 자체를 하지 않는다
    }

    @Test
    void 소유자는_자기_카드를_공개로_바꾼다() {
        Card card = new Card(1L, "제목", "요약", "왜 당신에게");   // 기본 PRIVATE
        when(cardRepository.findByPublicIdAndUserIdAndDeletedAtIsNull(any(), eq(1L)))
                .thenReturn(Optional.of(card));

        CardResponse res = service.changeVisibility(1L, UUID.randomUUID().toString(), "PUBLIC");

        assertThat(card.getVisibility()).isEqualTo("PUBLIC");
        assertThat(res.publicId()).isEqualTo(card.getPublicId());
    }

    @Test
    void 남의_카드는_존재_노출_없이_NOT_FOUND() {
        when(cardRepository.findByPublicIdAndUserIdAndDeletedAtIsNull(any(), any()))
                .thenReturn(Optional.empty());

        ApiException ex = catchThrowableOfType(
                () -> service.changeVisibility(1L, UUID.randomUUID().toString(), "PUBLIC"),
                ApiException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void 카드_도메인은_허용되지_않은_공개값을_거부한다() {
        Card card = new Card(1L, "제목", "요약", "왜 당신에게");

        assertThatThrownBy(() -> card.changeVisibility("BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
