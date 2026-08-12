package com.bambi.service.generation;

import com.bambi.service.user.User;
import com.bambi.service.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정의 변경점(Delta) 추적 설정을 읽는 단일 창구.
 *
 * <p><b>왜 별도 컴포넌트인가</b> — 2026-08-12 이전에는 온디맨드 생성 경로만 이 설정을 읽었고
 * 조회 로직이 {@code OnDemandGenerationService} 안의 private 메서드였다. "설정을 켜면
 * <b>모든 보고서</b>가 변경점 형식으로 나와야 한다"(2026-08-12 여진 요구)로 범위가 넓어지면서
 * 아침 브리핑·Wiki 관심사 리포트도 같은 값을 읽어야 한다. 세 곳에 같은 조회를 복사하면
 * 한 곳만 고쳐지는 순간 보고서 종류별로 설정이 갈린다.
 *
 * <p><b>없는 사용자는 꺼짐으로 다룬다.</b> 생성 요청 자체를 막을 이유는 인증 계층이 이미
 * 처리했고, 여기서 임의로 비용 큰 경로를 켜지 않는 쪽이 안전하다.
 */
@Component
public class ChangeHistorySettingReader {

    private final UserRepository userRepository;

    public ChangeHistorySettingReader(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * 이 사용자의 변경점 추적 설정({@code users.change_history_enabled}, V22).
     *
     * @param userId 대상 사용자
     * @return 켜져 있으면 true. 탈퇴·미존재 사용자는 false
     */
    @Transactional(readOnly = true)
    public boolean isEnabled(long userId) {
        return userRepository.findById(userId).map(User::isChangeHistoryEnabled).orElse(false);
    }
}
