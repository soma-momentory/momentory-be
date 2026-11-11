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
        LetterGateProperties.Ablation ab = properties.ablation();
        boolean riskEvent = materialsReader.riskSinceDiaryDay(letter);
        List<String> today = new ArrayList<>();
        today.add(m.diary());
        today.addAll(m.userWords());
        List<Diary> pastDiaries = ab.history() ? pastDiaries(letter) : List.of();
        List<PastDiary> past = pastDiaries.stream()
                .map(d -> new PastDiary(DayBoundary.toLocalDate(d.getCreatedAt()), cut(d.getOriginal())))
                .toList();
        if (!ab.temporal()) {
            // 비교 실험(P − 시간 정보 · B1): 지난 일기를 날짜 없이 오늘 글 뒤에 붙인다 — 규칙 검사도 판정도 한 덩어리로 본다.
            today.addAll(past.stream().map(PastDiary::text).toList());
            past = List.of();
            pastDiaries = List.of();
        }
        // 글마다 따로 검사한다 — 이어 붙이면 앞 글 끝과 뒤 글 앞이 붙어 없는 표현이 생긴다.
        SafetyLevel ruleToday = maxScan(today);
        SafetyLevel ruleHistory = maxScan(pastDiaries.stream().map(Diary::getOriginal).toList());

        Optional<RiskJudgment> judgment = Optional.empty();
        long startedAt = System.nanoTime();
        // 이미 QUIET 이 정해진 날(위기 안내 · 안전 기록 · 규칙 IMMINENT)은 판정자를 부르지 않는다(B1 은 규칙을 보지 않는다).
        boolean decidedByRules = m.crisis() || (!ab.simplePolicy() && ruleToday == SafetyLevel.IMMINENT);
        if (callJudge && !decidedByRules) {
            judgment = ab.temporal() ? judge.judge(m.letterId(), m.diary(), m.userWords(), past)
                    : judge.judgeSingle(m.letterId(), today);
        }
        long judgeMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        List<String> pastTexts = past.stream().map(PastDiary::text).toList();
        boolean usable = judgment.map(j -> j.usable(today, pastTexts)).orElse(false);
        LetterSafetyGate.Decision decision;
        if (ab.simplePolicy()) {
            decision = simple(m.crisis(), judgment.filter(j -> usable));
        } else {
            // m.crisis() 는 위기 안내와 안전 기록을 함께 담는다(P0) — 줄 이름만 나눠 남긴다.
            LetterSafetyGate.Input input = new LetterSafetyGate.Input(m.crisis() && !riskEvent, riskEvent, ruleToday,
                    ruleHistory, judgment, usable);
            decision = LetterSafetyGate.decide(input, properties.pastOnlyPolicy());
        }
        return new Assessment(decision, judgment, usable, ruleToday, ruleHistory, past.size(), judgeMillis);
    }

    /**
     * 비교 실험 B1 의 단순 정책 — 지금 운영(위기 안내 · 안전 기록이면 QUIET) 위에 판정 등급 하나만 얹는다. 판정이 실패하거나
     * 쓸 수 없으면 지금 운영처럼 NORMAL 이다(「LLM 분류기를 하나 붙인다」는 흔한 방식).
     */
    private static LetterSafetyGate.Decision simple(boolean crisis, Optional<RiskJudgment> judgment) {
        if (crisis) {
            return new LetterSafetyGate.Decision(LetterPolicy.QUIET, "B1-crisis");
        }
        if (judgment.isEmpty()) {
            return new LetterSafetyGate.Decision(LetterPolicy.NORMAL, "B1-unusable");
        }
        SafetyLevel level = judgment.get().todayCurrent();
        return level.atLeast(SafetyLevel.RISK) ? new LetterSafetyGate.Decision(LetterPolicy.QUIET, "B1-risk")
                : level == SafetyLevel.CAUTION ? new LetterSafetyGate.Decision(LetterPolicy.GENTLE, "B1-caution")
                : new LetterSafetyGate.Decision(LetterPolicy.NORMAL, "B1-none");
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
