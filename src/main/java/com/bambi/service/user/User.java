package com.bambi.service.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 사용자. service.users 에 매핑.
 * public_id(UUID) 는 DB default(gen_random_uuid())가 채우므로 쓰기는 하지 않고 읽기 전용으로만 매핑한다
 * (대외 식별자 — SNS 팔로우/공개 프로필에서 순번 id 노출 없이 사용자를 가리킨다).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // DB default(gen_random_uuid()) 소유 → insert/update 대상에서 제외(읽기 전용).
    @Column(name = "public_id", insertable = false, updatable = false)
    private UUID publicId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(unique = true, length = 50)
    private String username;

    @Column(name = "display_name", length = 100)
    private String displayName;

    // 프로필 소개문(V8). 앱 검증 120자 — 컬럼은 여유 300.
    @Column(length = 300)
    private String bio;

    // 사용자 설정(V17). 카드 발행 시 적용될 기본 공개범위 — 초기값은 DB DEFAULT 'PRIVATE' 와 일치시켜
    // JPA insert 가 NULL/기본값으로 DEFAULT 를 덮어쓰지 않게 한다.
    @Column(name = "default_card_visibility", length = 20, nullable = false)
    private String defaultCardVisibility = "PRIVATE";

    // 리포트 완료(REPORT_READY) 알림 수신 여부 — 초기값 true 는 DB DEFAULT TRUE 와 일치.
    @Column(name = "report_ready_notification", nullable = false)
    private boolean reportReadyNotification = true;

    // 변경점(Delta) 추적 계정 설정(V22, 김기용 08-10). 요청 단위 토글을 대체한다 —
    // true 면 온디맨드 생성에 change_history_enabled 를 싣는다. 초기값 false 는 DB DEFAULT 와 일치
    // (델타는 LLM 호출이 많은 경로라 명시적 opt-in).
    @Column(name = "change_history_enabled", nullable = false)
    private boolean changeHistoryEnabled = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    // agent 컨텍스트 동기화 버전(단조 증가). 가입 시 0 → 첫 동기화에서 1. agent 계약 §4.3.
    @Column(name = "agent_context_version", nullable = false)
    private int agentContextVersion;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected User() {
    }

    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    public void addRole(Role role) {
        this.roles.add(role);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getBio() {
        return bio;
    }

    public String getDefaultCardVisibility() {
        return defaultCardVisibility;
    }

    public boolean isReportReadyNotification() {
        return reportReadyNotification;
    }

    public boolean isChangeHistoryEnabled() {
        return changeHistoryEnabled;
    }

    /**
     * 프로필 편집(PUT /api/users/me). username 유일성 검증은 서비스 책임 —
     * 여기서는 값 반영만 한다. null username 은 "핸들 미설정 유지"가 아니라
     * 서비스가 미변경 판단 후 호출하지 않는 방식이라 도달하지 않는다.
     */
    public void updateProfile(String displayName, String bio) {
        this.displayName = displayName;
        this.bio = bio;
    }

    public void changeUsername(String username) {
        this.username = username;
    }

    /**
     * 비밀번호 변경 — 새 해시로 교체(현재 비밀번호 검증은 서비스 책임).
     * ⚠️ stateless JWT 라 변경 후에도 기존 토큰은 만료까지 유효하다(무효화 수단 없음 — tokenVersion 범위 밖).
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    /**
     * 사용자 설정 부분 변경(PATCH) — null 인 항목은 변경하지 않는다.
     * defaultCardVisibility 값 검증(PRIVATE/PUBLIC)은 서비스 책임.
     */
    public void updateSettings(String defaultCardVisibility, Boolean reportReadyNotification,
                               Boolean changeHistoryEnabled) {
        if (defaultCardVisibility != null) {
            this.defaultCardVisibility = defaultCardVisibility;
        }
        if (reportReadyNotification != null) {
            this.reportReadyNotification = reportReadyNotification;
        }
        if (changeHistoryEnabled != null) {
            this.changeHistoryEnabled = changeHistoryEnabled;
        }
    }

    /**
     * agent 컨텍스트 동기화 버전을 하나 올리고(단조 증가) 새 값을 반환한다.
     * 동기화 직전에 호출해 이 값을 {@code context_version} 으로 보낸다(계약 §4.3).
     */
    public int bumpAgentContextVersion() {
        return ++this.agentContextVersion;
    }

    /**
     * agent 가 409 STALE 로 알려준 현재 버전에 맞춰 다음 버전으로 점프한다(두 카운터 정합).
     * service 로컬 카운터와 agent 실제 버전이 어긋났을 때 재전송용으로 쓴다. {@code max} 로 역행을 막는다.
     */
    public int reconcileAgentContextVersion(int agentCurrentVersion) {
        this.agentContextVersion = Math.max(this.agentContextVersion, agentCurrentVersion) + 1;
        return this.agentContextVersion;
    }

    public int getAgentContextVersion() {
        return agentContextVersion;
    }

    /** 관리자 활성화 — 비활성(soft delete) 표시를 지운다. */
    public void activate() {
        this.deletedAt = null;
    }

    /**
     * 관리자 비활성화 — soft delete 시각을 찍어 비활성 처리한다. 이미 비활성이면 시각을 덮어쓰지 않는다.
     * 효과: 관리자 목록 INACTIVE 표시 + 생성 스케줄러 대상(deletedAt IS NULL)에서 제외.
     */
    public void deactivate() {
        if (this.deletedAt == null) {
            this.deletedAt = OffsetDateTime.now();
        }
    }

    public boolean isActive() {
        return deletedAt == null;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getDeletedAt() {
        return deletedAt;
    }

    public Set<Role> getRoles() {
        return roles;
    }
}
