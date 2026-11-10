package com.momentory.letter.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.common.time.DayBoundary;
import com.momentory.diary.domain.Diary;
import com.momentory.diary.infrastructure.DiaryRepository;
import com.momentory.letter.domain.Letter;
import com.momentory.letter.domain.LetterMaterials;
import com.momentory.letter.domain.LetterPolicy;
import com.momentory.letter.infrastructure.ai.GeminiRiskJudge;
import com.momentory.letter.infrastructure.ai.GeminiRiskJudge.PastDiary;
import com.momentory.retrospect.domain.safety.SafetyLevel;
import com.momentory.retrospect.domain.safety.SafetyPolicy;

/**
 * 결정표에 넣을 재료를 모으고 정책을 고른다 — 규칙 검사(오늘 글 · 지난 일기), 안전 기록, 판정자.
 *
 * <p>지난 일기는 판정자에만 넘기고 하루치 앞부분만 자른다(데이터 최소화). 규칙 검사는 자르기 전 원문으로 하지만
 * 그 결과는 서버 밖으로 나가지 않는다.
 */
@Component
class LetterSafetyAssessor {

    record Assessment(LetterSafetyGate.Decision decision, Optional<RiskJudgment> judgment, boolean usable,
            SafetyLevel ruleToday, SafetyLevel ruleHistory, int pastDiaries, long judgeMillis) {

        LetterPolicy policy() {
            return decision.policy();
        }
    }

    private final LetterMaterialsReader materialsReader;
    private final DiaryRepository diaryRepository;
    private final SafetyPolicy safetyPolicy;
    private final GeminiRiskJudge judge;
    private final LetterGateProperties properties;

    LetterSafetyAssessor(LetterMaterialsReader materialsReader, DiaryRepository diaryRepository,
            SafetyPolicy safetyPolicy, GeminiRiskJudge judge, LetterGateProperties properties) {
        this.materialsReader = materialsReader;
        this.diaryRepository = diaryRepository;
        this.safetyPolicy = safetyPolicy;
        this.judge = judge;
        this.properties = properties;
    }

    /**
     * @param callJudge 판정자를 부를까 — 열 때 보충은 8초 예산이라 부르지 않는다(판정 없음 → D4 줄)
     */
    Assessment assess(Letter letter, LetterMaterials m, boolean callJudge) {
        boolean riskEvent = materialsReader.riskSinceDiaryDay(letter);
        List<String> today = new ArrayList<>();
        today.add(m.diary());
        today.addAll(m.userWords());
        // 글마다 따로 검사한다 — 이어 붙이면 앞 글 끝과 뒤 글 앞이 붙어 없는 표현이 생긴다.
        SafetyLevel ruleToday = maxScan(today);
        List<Diary> pastDiaries = pastDiaries(letter);
        SafetyLevel ruleHistory = maxScan(pastDiaries.stream().map(Diary::getOriginal).toList());
        List<PastDiary> past = pastDiaries.stream()
                .map(d -> new PastDiary(DayBoundary.toLocalDate(d.getCreatedAt()), cut(d.getOriginal())))
                .toList();

        Optional<RiskJudgment> judgment = Optional.empty();
        long startedAt = System.nanoTime();
        // 이미 QUIET 이 정해진 날(위기 안내 · 안전 기록 · 규칙 IMMINENT)은 판정자를 부르지 않는다.
        boolean decidedByRules = m.crisis() || ruleToday == SafetyLevel.IMMINENT;
        if (callJudge && !decidedByRules) {
            judgment = judge.judge(m.letterId(), m.diary(), m.userWords(), past);
        }
        long judgeMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        boolean usable = judgment.map(j -> j.usable(today, past.stream().map(PastDiary::text).toList()))
                .orElse(false);
        // m.crisis() 는 위기 안내와 안전 기록을 함께 담는다(P0) — 줄 이름만 나눠 남긴다.
        LetterSafetyGate.Input input = new LetterSafetyGate.Input(m.crisis() && !riskEvent, riskEvent, ruleToday,
                ruleHistory, judgment, usable);
        return new Assessment(LetterSafetyGate.decide(input, properties.pastOnlyPolicy()), judgment, usable,
                ruleToday, ruleHistory, past.size(), judgeMillis);
    }

    /** 일기 날 전 N일의 일기, 날짜순(이 편지의 일기는 뺀다). */
    private List<Diary> pastDiaries(Letter letter) {
        return diaryRepository.findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
                letter.getUserId(),
                DayBoundary.startOfDay(letter.getLetterDate().minusDays(properties.pastDiaryDays())),
                DayBoundary.startOfDay(letter.getLetterDate())).stream()
                .filter(d -> !d.getId().equals(letter.getDiaryId()) && d.getOriginal() != null)
                .sorted(Comparator.comparing(Diary::getCreatedAt))
                .toList();
    }

    private String cut(String text) {
        return text.length() <= properties.pastDiaryChars() ? text : text.substring(0, properties.pastDiaryChars());
    }

    private SafetyLevel maxScan(List<String> texts) {
        return texts.stream().map(t -> safetyPolicy.scan(t).level()).reduce(SafetyLevel.NONE, SafetyLevel::max);
    }
}
