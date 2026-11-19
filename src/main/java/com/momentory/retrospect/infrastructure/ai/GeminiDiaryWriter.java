package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;

/**
 * 일기 생성 어댑터 — 선정된 주요 사건 최대 두 개를 사용자가 말한 사실·감정만으로 최소 5문장·사건별 문단의 1인칭 일기로 풀어 쓴다(G4).
 * 모델에게는 문단(사건)마다 문장 목록을 받고, 줄바꿈은 {@link DiaryDraft} 가 만든다.
 * 5문장보다 짧으면 빠진 말을 담아 한 번만 다시 쓰게 한다. 최초 생성 실패는 empty(엔진의 최소 일기 대체),
 * 다시 쓰기 실패나 여전히 짧은 결과는 버리고 최초 초안을 유지한다.
 */
@Component
public class GeminiDiaryWriter implements DiaryWriter {

    private static final Logger log = LoggerFactory.getLogger(GeminiDiaryWriter.class);

    static final int MIN_SENTENCES = 5;
    static final int MAX_SENTENCES = 8;
    /** 마지막 턴은 G1·G4가 이어서 돌기 때문에 앱의 회고 API 한도(45초) 안에 들도록 다시 쓰기만 짧게 기다린다. */
    static final Duration REVISION_TIMEOUT = Duration.ofSeconds(10);

    private final GeminiApiClient llm;
    private final PromptFactory prompts;

    public GeminiDiaryWriter(GeminiApiClient llm, PromptFactory prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    @Override
    public Optional<DiaryOutput> write(RetrospectState state) {
        LlmRequest request = LlmRequest.of(state, LlmRole.G4, prompts.system(),
                prompts.diaryPrompt(state));
        Optional<DiaryDraft> generated = llm.generate(request, DiaryDraft.class);
        Optional<DiaryDraft> first = generated.map(draft -> draft.forEvents(state.events())).filter(DiaryDraft::hasText);
        if (generated.isPresent() && first.isEmpty() && !state.events().isEmpty()) {
            // 사건 이름은 모델이 조금 바꿔 쓸 수 있다. 번호를 기준으로 연결하고, 번호 누락·오류는 한 번만 복구한다.
            // 본문·사건 이름을 로그에 남기지 않는다.
            log.warn("Gemini 일기 사건 연결 검증 실패 session={} selectedEvents={}", state.id(), state.events().size());
            String repair = request.user() + """

                    [사건 연결 다시 쓰기]
                    이전 출력은 주요 사건에 연결할 수 없었습니다. 위 대화를 바탕으로 일기를 다시 쓰세요.
                    paragraphs에는 [주요 사건]의 각 사건을 빠짐없이 한 번씩 담습니다.
                    eventId는 지정된 사건 번호를 그대로 사용하고 새로 번호를 매기지 마세요.
                    사건 요약만 반환하지 말고, 그 사건에서 사용자가 말한 상황·생각·감정을 5~8문장으로 풀어 쓰세요.
                    다른 사건이나 사실은 추가하지 마세요.
                    """;
            return llm.withReadTimeout(REVISION_TIMEOUT)
                    .generate(LlmRequest.of(state, LlmRole.G4, prompts.system(), repair), DiaryDraft.class)
                    .map(draft -> draft.forEvents(state.events()))
                    .filter(DiaryDraft::hasText)
                    .map(GeminiDiaryWriter::toOutput);
        }
        if (first.isEmpty() || first.get().sentenceCount() >= MIN_SENTENCES) {
            return first.map(GeminiDiaryWriter::toOutput);
        }
        // 새 경험은 만들지 않고, 초안에서 빠진 사용자의 말을 찾아 풀어 쓰게 한다. 한 번만 다시 쓰며
        // 실패하거나 더 길어지지 않으면 최초 초안을 잃지 않는다.
        String revision = request.user() + """

                [다시 쓰기]
                아래 초안은 %d문장보다 짧습니다. 위에 지정한 주요 사건 안에서만 사용자가 한 말 중 초안에 빠진 것을 찾아 담고,
                사용자가 한 말마다 그때의 상황과 생각, 마음을 풀어 %d~%d문장으로 다시 쓰세요.
                문단은 사건 단위로만 나누고, 같은 사건의 문장은 한 문단에 담으세요.
                대화에 없던 사건·인물·장소·행동은 지어내지 말고, 같은 문장을 되풀이해 분량을 채우지 마세요.
                [초안]
                """.formatted(MIN_SENTENCES, MIN_SENTENCES, MAX_SENTENCES) + first.get().text();
        return llm.withReadTimeout(REVISION_TIMEOUT)
                .generate(LlmRequest.of(state, LlmRole.G4, prompts.system(), revision), DiaryDraft.class)
                .map(draft -> draft.forEvents(state.events()))
                .filter(DiaryDraft::hasText)
                .filter(revised -> revised.sentenceCount() > first.get().sentenceCount())
                .or(() -> first)
                .map(GeminiDiaryWriter::toOutput);
    }

    private static DiaryOutput toOutput(DiaryDraft draft) {
        return new DiaryOutput(draft.text(), null);
    }
}
