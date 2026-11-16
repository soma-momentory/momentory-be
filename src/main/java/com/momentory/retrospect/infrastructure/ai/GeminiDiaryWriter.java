package com.momentory.retrospect.infrastructure.ai;

import java.time.Duration;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.momentory.retrospect.domain.RetrospectState;
import com.momentory.retrospect.domain.assistant.DiaryOutput;
import com.momentory.retrospect.domain.assistant.DiaryWriter;

/**
 * 일기 생성 어댑터 — 대화 전체를 사용자가 말한 사실·감정만으로 최소 5문장·사건별 문단의 1인칭 일기로 풀어 쓴다(G4).
 * 모델에게는 문단(사건)마다 문장 목록을 받고, 줄바꿈은 {@link DiaryDraft} 가 만든다.
 * 5문장보다 짧으면 빠진 말을 담아 한 번만 다시 쓰게 한다. 최초 생성 실패는 empty(엔진의 최소 일기 대체),
 * 다시 쓰기 실패나 여전히 짧은 결과는 버리고 최초 초안을 유지한다.
 */
@Component
public class GeminiDiaryWriter implements DiaryWriter {

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
        Optional<DiaryDraft> first = llm.generate(request, DiaryDraft.class).filter(DiaryDraft::hasText);
        if (first.isEmpty() || first.get().sentenceCount() >= MIN_SENTENCES) {
            return first.map(GeminiDiaryWriter::toOutput);
        }
        // 새 경험은 만들지 않고, 초안에서 빠진 사용자의 말을 찾아 풀어 쓰게 한다. 한 번만 다시 쓰며
        // 실패하거나 더 길어지지 않으면 최초 초안을 잃지 않는다.
        String revision = request.user() + """

                [다시 쓰기]
                아래 초안은 %d문장보다 짧습니다. 위 대화에서 사용자가 한 말 중 초안에 빠진 것을 찾아 담고,
                사용자가 한 말마다 그때의 상황과 생각, 마음을 풀어 %d~%d문장으로 다시 쓰세요.
                문단은 사건 단위로만 나누고, 같은 사건의 문장은 한 문단에 담으세요.
                대화에 없던 사건·인물·장소·행동은 지어내지 말고, 같은 문장을 되풀이해 분량을 채우지 마세요.
                [초안]
                """.formatted(MIN_SENTENCES, MIN_SENTENCES, MAX_SENTENCES) + first.get().text();
        return llm.withReadTimeout(REVISION_TIMEOUT)
                .generate(LlmRequest.of(state, LlmRole.G4, prompts.system(), revision), DiaryDraft.class)
                .filter(DiaryDraft::hasText)
                .filter(revised -> revised.sentenceCount() > first.get().sentenceCount())
                .or(() -> first)
                .map(GeminiDiaryWriter::toOutput);
    }

    private static DiaryOutput toOutput(DiaryDraft draft) {
        return new DiaryOutput(draft.text(), null);
    }
}
