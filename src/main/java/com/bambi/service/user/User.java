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
     * agent 컨텍스트 동기화 버전을 하나 올리고(단조 증가) 새 값을 반환한다.
     * 동기화 직전에 호출해 이 값을 {@code context_version} 으로 보낸다(계약 §4.3).
     */
    public int bumpAgentContextVersion() {
        return ++this.agentContextVersion;
    }

    public int getAgentContextVersion() {
        return agentContextVersion;
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
