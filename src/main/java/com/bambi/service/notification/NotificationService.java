package com.bambi.service.notification;

import com.bambi.service.common.error.ApiException;
import com.bambi.service.common.error.ErrorCode;
import com.bambi.service.notification.dto.NotificationListResponse;
import com.bambi.service.notification.dto.NotificationResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 리포트 발행 완료 알림 생성과 사용자 Inbox 조회를 처리한다. */
@Service
public class NotificationService {

    private static final int LIST_LIMIT = 50;

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * Agent 리포트가 Service DB에 신규 저장된 뒤 멱등 완료 알림을 만든다.
     * @param reportType 생성 유형(MORNING_BRIEFING|ON_DEMAND) — claim 페이로드에 없으면 null(관용).
     */
    @Transactional
    public void notifyReportReady(
            Long userId,
            String contentId,
            Integer version,
            String title,
            String summary,
            UUID reportPublicId,
            String reportType) {
        notificationRepository.insertReportReady(
                userId,
                "report-ready:" + contentId + ":v" + version,
                truncate("새 리포트가 준비됐어요: " + title, 200),
                truncate(summary, 500),
                "/report/" + reportPublicId,
                reportType);
    }

    /** 최근 알림과 읽지 않은 개수를 반환한다. */
    @Transactional(readOnly = true)
    public NotificationListResponse list(Long userId) {
        var items = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, LIST_LIMIT))
                .stream()
                .map(NotificationResponse::from)
                .toList();
        return new NotificationListResponse(
                notificationRepository.countByUserIdAndReadAtIsNull(userId), items);
    }

    /** 소유한 알림 하나를 읽음 처리한다. */
    @Transactional
    public NotificationResponse markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "알림을 찾을 수 없습니다."));
        notification.markRead();
        return NotificationResponse.from(notification);
    }

    /** Agent 문자열이 Inbox 컬럼 길이를 넘어 발행 트랜잭션을 깨지 않게 제한한다. */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
