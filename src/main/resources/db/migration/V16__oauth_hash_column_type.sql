-- =============================================================
-- V16 — OAuth 해시 컬럼을 CHAR(64) → VARCHAR(64) 로 정정
--
-- V15 가 세 해시 컬럼을 CHAR(64) 로 만들었는데, JPA 엔티티는 @Column(length = 64) 인
-- String 이라 Hibernate 가 varchar(64) 를 기대한다. ddl-auto=validate 가 이 불일치를
-- 잡아 SessionFactory 생성에 실패하고 **Spring 이 아예 기동하지 못했다** —
-- 2026-08-07 #54 배포 직후 backend 가 뜨지 못해 /api/* 전체가 502 였다.
--
--   Schema-validation: wrong column type encountered in column
--   [authorization_code_hash] in table [oauth_authorizations];
--   found [bpchar (Types#CHAR)], but expecting [varchar(64) (Types#VARCHAR)]
--
-- 운영 DB 는 같은 ALTER 를 수동 실행해 이미 복구했다. 이 Migration 은 그 조치를
-- 파일로 남겨 신규 환경에서 같은 기동 실패가 재발하지 않게 한다.
-- (V15 는 이미 적용된 이력이라 수정할 수 없어 별도 버전으로 올린다.)
--
-- 저장값은 SHA-256 hex 로 항상 정확히 64자라 CHAR 의 공백 패딩이 없었고,
-- 세 테이블 모두 이번 배포에서 처음 생성돼 데이터도 없다 → 값 변화 없음.
-- =============================================================

ALTER TABLE service.oauth_authorizations
    ALTER COLUMN authorization_code_hash TYPE VARCHAR(64);

ALTER TABLE service.oauth_tokens
    ALTER COLUMN access_token_hash TYPE VARCHAR(64);

ALTER TABLE service.oauth_tokens
    ALTER COLUMN refresh_token_hash TYPE VARCHAR(64);
