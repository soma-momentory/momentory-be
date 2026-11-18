package com.momentory.letter.domain;

import java.time.Instant;

import com.momentory.common.persistence.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 바바의 그림일기 — 편지 한 통에 4컷 그림 한 장. 행은 저장소의 조건부 삽입({@code claim})이 GENERATING 으로 만들고,
 * 이 엔티티는 다 그린 결과를 채우거나 실패로 닫는 일만 한다.
 */
@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "letter_pictures")
public class LetterPicture extends BaseTimeEntity {

    @Id
    @Column(name = "letter_id")
    private Long letterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LetterPictureStatus status;

    /** JPEG 바이트. READY 일 때만 있다. */
    @Column(name = "image")
    private byte[] image;

    @Column(name = "mime_type", length = 32)
    private String mimeType;

    /** 1단계 장면 나누기 결과(JSON) — 그림이 이상할 때 어느 단계 탓인지 본다. */
    @Column(columnDefinition = "TEXT")
    private String scenes;

    @Column(name = "plan_model", length = 60)
    private String planModel;

    @Column(name = "image_model", length = 60)
    private String imageModel;

    @Column(name = "prompt_version", nullable = false, length = 20)
    private String promptVersion;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "generated_at")
    private Instant generatedAt;

    protected LetterPicture() {
    }

    /** 다 그렸다. */
    public void complete(byte[] jpeg, String mimeType, String scenes, String planModel, String imageModel,
            Instant now) {
        if (jpeg == null || jpeg.length == 0 || mimeType == null) {
            throw new IllegalArgumentException("그림이 비어 있습니다");
        }
        this.status = LetterPictureStatus.READY;
        this.image = jpeg.clone();
        this.mimeType = mimeType;
        this.scenes = scenes;
        this.planModel = planModel;
        this.imageModel = imageModel;
        this.generatedAt = now;
    }

    /** 만들지 못했다. 장면까지 나눴으면 남겨 둔다. */
    public void fail(String scenes, Instant now) {
        this.status = LetterPictureStatus.FAILED;
        this.scenes = scenes;
        this.generatedAt = now;
    }

    public boolean isReady() {
        return status == LetterPictureStatus.READY;
    }

    public Long getLetterId() {
        return letterId;
    }

    public LetterPictureStatus getStatus() {
        return status;
    }

    public byte[] getImage() {
        return image == null ? null : image.clone();
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getScenes() {
        return scenes;
    }

    public Instant getStartedAt() {
        return startedAt;
    }
}
