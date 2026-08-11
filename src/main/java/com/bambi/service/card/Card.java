package com.bambi.service.card;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 브리핑 카드 — 사용자에게 노출되는 최종 데이터 (service.cards).
 * 대외 식별자는 public_id(UUID) 를 쓴다 (순번 노출 방지 — V1 설계 의도).
 * 출처(card_sources)는 카드가 소유하는 aggregate 로, 카드 저장 시 함께 저장된다.
 */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column
    private String summary;

    @Column(name = "why_for_you")
    private String whyForYou;

    @Column(nullable = false, length = 20)
    private String visibility;

    // 비동기 발행(agent Pull) 멱등 키. 동기 "즉시 카드"는 둘 다 null.
    @Column(name = "external_content_id", length = 200)
    private String externalContentId;

    @Column(name = "external_version")
    private Integer externalVersion;

    // 본문(리포트) 참조. 카드는 요약 전용이고 body 는 reports 에 있다(§3.1). 동기 카드는 null 가능.
    @Column(name = "report_id")
    private Long reportId;

    // 생성 유형(MORNING_BRIEFING|ON_DEMAND). 리포트와 같은 값을 발행 시점에 복제 저장 —
    // CardResponse 가 리포트 조인 없이 서빙되는 경로(즉시 카드 등)에서도 노출 가능하게.
    @Column(name = "report_type", length = 30)
    private String reportType;

    // 관심사 태그 (why_for_you 폐기 대체) — agent 가 붙인 topic 문자열 집합.
    @ElementCollection
    @CollectionTable(name = "card_interest_tags", joinColumns = @JoinColumn(name = "card_id"))
    @Column(name = "tag", nullable = false, length = 100)
    @BatchSize(size = 100)
    private Set<String> interestTags = new LinkedHashSet<>();

    // @BatchSize: 공개피드처럼 fetch join 없이 여러 카드를 페이징 조회할 때, 각 카드의 sources 를
    // 카드당 1쿼리(N+1)가 아니라 IN (…) 한 번으로 묶어 로딩한다(최대 100건). 인메모리 페이지네이션 회피.
    @OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
    @BatchSize(size = 100)
    private List<CardSource> sources = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    protected Card() {
    }

    public Card(Long userId, String title, String summary, String whyForYou) {
        this.publicId = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.summary = summary;
        this.whyForYou = whyForYou;
        this.visibility = "PRIVATE";   // 공개 피드는 P1 — 기본 비공개
    }

    /** agent 발행(Pull) 카드 — content_id/version 멱등 키를 함께 보관한다. */
    public static Card fromExternal(Long userId, String contentId, Integer version,
                                    String title, String summary, String whyForYou) {
        Card card = new Card(userId, title, summary, whyForYou);
        card.externalContentId = contentId;
        card.externalVersion = version;
        return card;
    }

    public void addSource(String title, String url) {
        sources.add(new CardSource(this, title, url));
    }

    /**
     * 발행 재수신(더 큰 version)으로 카드 내용을 갱신한다.
     * sources 는 통째로 교체(orphanRemoval 로 이전 것 삭제). 호출부가 새 sources 를 다시 추가한다.
     */
    public void updateExternal(Integer version, String title, String summary) {
        this.externalVersion = version;
        this.title = title;
        this.summary = summary;
        this.sources.clear();
    }

    /** 카드를 리포트(본문)에 연결한다. */
    public void linkReport(Long reportId) {
        this.reportId = reportId;
    }

    /**
     * 생성 유형 반영(신규·재수신 공통). Report.applyReportType 과 같은 관용 정책 —
     * 값 없는 재발행이 이미 저장된 유형을 지우지 않게 null 은 무시한다.
     */
    public void applyReportType(String reportType) {
        if (reportType != null) {
            this.reportType = reportType;
        }
    }

    public void addInterestTag(String tag) {
        if (tag != null && !tag.isBlank()) {
            interestTags.add(tag.strip());
        }
    }

    /** 관심사 태그를 통째로 교체(발행 재수신 시). */
    public void replaceInterestTags(java.util.Collection<String> tags) {
        interestTags.clear();
        if (tags != null) {
            tags.forEach(this::addInterestTag);
        }
    }

    /**
     * 카드 공개설정 변경 (SNS 카드 공개/비공개 스위치).
     * 허용값은 V1 CHECK 제약(PRIVATE/PUBLIC)과 일치. 잘못된 값은 저장 전에 막는다.
     */
    public void changeVisibility(String visibility) {
        if (!"PUBLIC".equals(visibility) && !"PRIVATE".equals(visibility)) {
            throw new IllegalArgumentException("visibility must be PUBLIC or PRIVATE: " + visibility);
        }
        this.visibility = visibility;
    }

    public String getVisibility() {
        return visibility;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public String getWhyForYou() {
        return whyForYou;
    }

    public List<CardSource> getSources() {
        return sources;
    }

    public Integer getExternalVersion() {
        return externalVersion;
    }

    public String getExternalContentId() {
        return externalContentId;
    }

    public Long getReportId() {
        return reportId;
    }

    public String getReportType() {
        return reportType;
    }

    public Set<String> getInterestTags() {
        return interestTags;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
