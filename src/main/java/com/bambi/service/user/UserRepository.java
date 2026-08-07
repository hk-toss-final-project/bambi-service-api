package com.bambi.service.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** 관리자 대시보드 — 살아있는(활성) 사용자 수. 전체 수는 {@code count()}. */
    long countByDeletedAtIsNull();

    /** 관리자 대시보드 — 비활성(soft delete) 사용자 수. */
    long countByDeletedAtIsNotNull();

    /** 관리자 대시보드 — 지정 시각 이후 가입 수(오늘 가입 집계). */
    long countByCreatedAtGreaterThanEqual(OffsetDateTime from);

    /** 관리자 목록용 — 전체 사용자를 가입 최신순으로. */
    List<User> findAllByOrderByCreatedAtDesc();

    /** 대외 식별자(public_id)로 살아있는 사용자 조회 — SNS 팔로우/프로필 진입점. */
    Optional<User> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** 팔로워/팔로잉 목록용 — 살아있는 사용자만 배치 조회(탈퇴 계정은 목록에서 제외). */
    List<User> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    /** 핸들(username) 중복 검사 — 프로필 편집에서 본인 제외. DB unique 가 최종 방어. */
    boolean existsByUsernameAndIdNot(String username, Long id);

    /** 살아있는 사용자 id 목록 — 생성 스케줄러가 사용자별 생성 요청을 돌릴 때 쓴다(엔티티 전체 로딩 회피). */
    @Query("select u.id from User u where u.deletedAt is null")
    List<Long> findAllActiveIds();
}
