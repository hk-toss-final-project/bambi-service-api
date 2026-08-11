package com.bambi.service.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 알림 조회와 멱등 생성을 담당하는 저장소. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /** 같은 Agent 발행 이벤트를 재수신해도 알림을 한 번만 만든다. reportType 은 null 허용(관용). */
    @Modifying
    @Query(value = """
            INSERT INTO service.notifications (
                user_id, event_key, type, title, body, target_path, report_type
            ) VALUES (
                :userId, :eventKey, 'REPORT_READY', :title, :body, :targetPath, :reportType
            )
            ON CONFLICT (user_id, event_key) DO NOTHING
            """, nativeQuery = true)
    int insertReportReady(
            @Param("userId") Long userId,
            @Param("eventKey") String eventKey,
            @Param("title") String title,
            @Param("body") String body,
            @Param("targetPath") String targetPath,
            @Param("reportType") String reportType);

    /**
     * 팔로우 알림 (2026-08-11 여진 요청 — FOLLOW 타입, 마이그레이션 불요).
     * event_key 가 (user_id, event_key) UNIQUE 라 같은 사람의 팔로우↔언팔 반복은
     * 알림을 한 번만 만든다 — 알림 스팸·어뷰징을 DB 가 막는다. report_type 은 리포트 전용이라 null.
     */
    @Modifying
    @Query(value = """
            INSERT INTO service.notifications (
                user_id, event_key, type, title, body, target_path
            ) VALUES (
                :userId, :eventKey, 'FOLLOW', :title, null, :targetPath
            )
            ON CONFLICT (user_id, event_key) DO NOTHING
            """, nativeQuery = true)
    int insertFollow(
            @Param("userId") Long userId,
            @Param("eventKey") String eventKey,
            @Param("title") String title,
            @Param("targetPath") String targetPath);

    /**
     * 좋아요 알림 (2026-08-11 여진 요청 — LIKE 타입, 마이그레이션 불요).
     * event_key 가 카드×행위자 단위라 좋아요↔취소 반복은 알림을 한 번만 만든다(FOLLOW 와 동일 정책).
     */
    @Modifying
    @Query(value = """
            INSERT INTO service.notifications (
                user_id, event_key, type, title, body, target_path
            ) VALUES (
                :userId, :eventKey, 'LIKE', :title, null, :targetPath
            )
            ON CONFLICT (user_id, event_key) DO NOTHING
            """, nativeQuery = true)
    int insertLike(
            @Param("userId") Long userId,
            @Param("eventKey") String eventKey,
            @Param("title") String title,
            @Param("targetPath") String targetPath);
}
