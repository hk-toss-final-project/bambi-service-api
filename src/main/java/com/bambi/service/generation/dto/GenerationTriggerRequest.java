package com.bambi.service.generation.dto;

import jakarta.validation.constraints.Size;

/**
 * 즉시 생성 요청 body (POST /api/reports/generate) — 2026-08-06 계약(여진·소라·우석).
 * topic = 사용자가 홈 rail 에서 고른 관심사 이름. 온보딩 관심사(service)와 LLM Wiki 관심사(agent 태그)의
 * ID 체계가 달라 둘을 통일하는 값은 이름 문자열이다. 길이 제약은 관심사 name(1~100자)과 동일.
 *
 * <p>body 자체가 optional 이다 — 없거나 topic 이 비어 있으면 기존처럼 대표 관심사(score 최고)를
 * 자동 선택한다(하위호환). topic 이 오면 서버가 관심사 원자 처리(없으면 USER 로 추가) 후 생성한다.
 *
 * <p>변경점(Delta) 추적 필드는 여기 없다(2026-08-10 김기용 — 계정 설정
 * {@code users.change_history_enabled} 로 전환). 프론트 전송 코드가 0곳이라 하위호환 영향 없음.
 * 미지의 필드는 Jackson 이 무시하므로 옛 body 를 보내도 400 이 아니라 "설정값 우선"으로 동작한다.
 */
public record GenerationTriggerRequest(@Size(max = 100) String topic) {

    /** 정규화된 topic — 앞뒤 공백 제거, 비어 있으면 null(대표 관심사 자동 선택 신호). */
    public String normalizedTopic() {
        if (topic == null || topic.isBlank()) {
            return null;
        }
        return topic.strip();
    }
}
