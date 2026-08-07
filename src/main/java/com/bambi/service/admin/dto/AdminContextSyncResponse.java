package com.bambi.service.admin.dto;

import java.time.OffsetDateTime;

/**
 * 관리자 강제 재동기화 결과.
 *
 * 성공했을 때만 내려간다 — agent 가 받지 못하면 예외(AGENT_UNAVAILABLE)로 끝나므로,
 * 화면은 이 응답이 왔다는 것만으로 "밀어넣었다"고 말할 수 있다.
 * contextVersion 은 이번에 실려 나간 뒤 사용자에 남은 버전으로, agent 와 어긋났던
 * 계정은 여기서 값이 크게 뛴다(#47 버전 정합 재전송).
 */
public record AdminContextSyncResponse(
        Long userId,
        String email,
        int contextVersion,
        OffsetDateTime syncedAt) {
}
