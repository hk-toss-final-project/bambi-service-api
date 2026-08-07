package com.bambi.service.follow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface FollowRepository extends JpaRepository<Follow, FollowId> {

    boolean existsByFollowerIdAndFolloweeId(Long followerId, Long followeeId);

    /** 팔로워 수(=이 사용자를 팔로우하는 사람 수) */
    long countByFolloweeId(Long followeeId);

    /** 팔로잉 수(=이 사용자가 팔로우하는 사람 수) */
    long countByFollowerId(Long followerId);

    /** 팔로잉 대상 id 목록 — 팔로잉 스코프 공개피드에서 작성자 필터로 쓴다. 팔로잉 목록 화면도 재사용. */
    @Query("select f.followeeId from Follow f where f.followerId = :followerId")
    List<Long> findFolloweeIds(@Param("followerId") Long followerId);

    /** 이 사용자를 팔로우하는 사람들의 id (팔로워 목록 화면). */
    @Query("select f.followerId from Follow f where f.followeeId = :userId")
    List<Long> findFollowerIds(@Param("userId") Long userId);

    /** viewer 가 주어진 대상 목록 중 팔로우 중인 id (목록의 following 플래그 1 IN 쿼리 배치). */
    @Query("select f.followeeId from Follow f where f.followerId = :viewerId and f.followeeId in :userIds")
    List<Long> findFollowedAmong(@Param("viewerId") Long viewerId,
                                 @Param("userIds") Collection<Long> userIds);

    /**
     * 멱등 팔로우 — 이미 있으면 조용히 무시(ON CONFLICT DO NOTHING).
     * 동시 요청(더블클릭)에도 유니크 위반 예외 없이 안전. created_at 은 DB default(now()).
     */
    @Modifying
    @Query(value = "INSERT INTO service.follows(follower_id, followee_id) "
            + "VALUES (:followerId, :followeeId) ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertIgnore(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

    /** 언팔 — 없어도 0건, 멱등. */
    @Modifying
    @Query("delete from Follow f where f.followerId = :followerId and f.followeeId = :followeeId")
    int deleteRelation(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);
}
