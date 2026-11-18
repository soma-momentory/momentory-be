package com.momentory.letter.application;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.infrastructure.LetterPictureRepository;
import com.momentory.letter.infrastructure.LetterRepository;
import com.momentory.letter.infrastructure.ai.GeminiPictureDrawer;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner;
import com.momentory.letter.infrastructure.ai.GeminiScenePlanner.Plan;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * 바바의 그림일기를 만든다 — 회고 커밋 뒤 편지 실행기에서, 편지 글을 다 만든 바로 다음에 한 번(2026-11-18).
 *
 * <p>순서: 자격 확인 → 행 넣기(한쪽만) → 장면 나누기 → 그리기 → JPEG 로 줄여 저장. 한 단계라도 실패하면 FAILED 로
 * 닫고 다시 만들지 않는다 — 그림 없이 편지만 보인다. 글자 검사 · 재생성도 하지 않는다(비용 · 2026-11-18 결정).
 *
 * <p>자격: 기능이 켜졌고(사용자 목록 포함) 안전 판정을 통과한 AI 편지(READY)이며 조심스럽게 쓴 편지(GENTLE)가 아닐 때.
 * 조용한 편지 · 기본 편지에는 그리지 않는다. 열 때 보충은 그림을 만들지 않는다 — 기능을 켜기 전에 받은 편지에
 * 소급해 그리지 않기 위해서다(2026-11-18 결정).
 */
@Component
public class LetterPictureGenerator {

    static final String PROMPT_VERSION = "picture-v1";

    private final LetterRepository letterRepository;
    private final LetterPictureRepository pictureRepository;
    private final LetterMaterialsReader materialsReader;
    private final GeminiScenePlanner planner;
    private final GeminiPictureDrawer drawer;
    private final LetterPictureImages images;
    private final LetterPictureProperties properties;
    private final TransactionTemplate transaction;
    private final MeterRegistry meters;
    private final ObjectMapper objectMapper;

    public LetterPictureGenerator(LetterRepository letterRepository, LetterPictureRepository pictureRepository,
            LetterMaterialsReader materialsReader, GeminiScenePlanner planner, GeminiPictureDrawer drawer,
            LetterPictureImages images, LetterPictureProperties properties, TransactionTemplate transaction,
            MeterRegistry meters, ObjectMapper objectMapper) {
        this.letterRepository = letterRepository;
        this.pictureRepository = pictureRepository;
        this.materialsReader = materialsReader;
        this.planner = planner;
        this.drawer = drawer;
        this.images = images;
        this.properties = properties;
        this.transaction = transaction;
        this.meters = meters;
        this.objectMapper = objectMapper;
    }

    /** 회고 커밋 뒤 편지 실행기에서 부른다 — 편지를 다 만든 다음이라 편지 상태를 다시 읽어 판단한다. */
    public void generateForDiary(Long diaryId) {
        letterRepository.findByDiaryId(diaryId).ifPresent(this::generate);
    }

    void generate(Letter letter) {
        if (!eligible(letter)) {
            return;
        }
        if (pictureRepository.claim(letter.getId(), PROMPT_VERSION, Instant.now()) == 0) {
            return; // 이미 들어간 편지 — 다 그렸거나 실패했거나 그리는 중이다.
        }
        long startedAt = System.nanoTime();
        String result = "failed";
        String scenes = null;
        try {
            Optional<LetterMaterials> materials = materialsReader.read(letter);
            if (materials.isEmpty()) {
                result = "skipped"; // 만드는 사이 일기가 지워졌다 — 그림 행도 함께 지워졌다.
                return;
            }
            Optional<Plan> plan = planner.plan(letter.getId(), materials.get().diary());
            scenes = plan.map(objectMapper::writeValueAsString).orElse(null);
            Optional<byte[]> jpeg = plan.flatMap(p -> drawer.draw(letter.getId(), p))
                    .flatMap(drawn -> images.toJpeg(drawn.bytes()));
            if (jpeg.isPresent()) {
                save(letter.getId(), jpeg.get(), scenes);
                result = "ready";
            } else {
                fail(letter.getId(), scenes);
            }
        } catch (RuntimeException e) {
            fail(letter.getId(), scenes);
            throw e;
        } finally {
            meters.counter("letter.picture.generate", "result", result).increment();
            meters.timer("letter.picture.duration", "result", result)
                    .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    boolean eligible(Letter letter) {
        return properties.allows(letter.getUserId()) && letter.allowsPicture();
    }

    private void save(Long letterId, byte[] jpeg, String scenes) {
        transaction.executeWithoutResult(s -> pictureRepository.findById(letterId).ifPresent(p -> p.complete(jpeg,
                LetterPictureImages.JPEG, scenes, planner.model(), drawer.model(), Instant.now())));
    }

    private void fail(Long letterId, String scenes) {
        transaction.executeWithoutResult(s -> pictureRepository.findById(letterId)
                .filter(p -> !p.isReady())
                .ifPresent(p -> p.fail(scenes, Instant.now())));
    }
}
