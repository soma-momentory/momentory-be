package com.momentory.retrospect.domain.safety;

import java.util.List;
import java.util.Optional;

/**
 * 규칙 층 프롬프트 가드 — 구현 공개 요청·프롬프트 공격을 AI 호출 전에 흘려보낸다(STEP 13).
 *
 * <p>{@link SafetyPolicy}와 같은 철학이다 — 방어의 결정권을 AI 가 아니라 규칙층이 쥔다.
 * 공격자가 판정 자체를 조작하는 걸 막으려면 탐지는 결정적이어야 하고, AI(시스템 프롬프트 하드닝)는
 * 그 위에 얹는 백업층이다. 여기서 못 잡는 새 표현은 시스템 프롬프트의 '구현 비공개·지시 무시 금지'
 * 규칙이 받는다.
 *
 * <p><b>정밀도 우선(오탐 방지).</b> 감정 회고에서는 "그 사람이 저를 <u>무시</u>했어요"·"오늘
 * <u>백엔드</u> 작업했어요" 같은 문장이 정상 답변이다. 그래서 바 단어(무시/모델/규칙/백엔드)가
 * 아니라 <b>지시성 구문</b>("이전 지시 무시", "무슨 모델이야", "어떻게 구현")만 잡는다.
 * 아래 목록은 그 시드이며, 오탐이 나면 목록에서 빼고 프롬프트 하드닝에 맡긴다.
 */
public final class PromptGuard {

    private PromptGuard() {
    }

    /** 탐지 범주 — 되돌리는 문구를 고르는 데 쓴다. */
    public enum Category {
        /** 지시 무시·역할 변경·프롬프트 추출 등 공격 시도. */
        INJECTION,
        /** 사용 모델·내부 구현·시스템 지시 등 구현 정보 공개 요청. */
        META
    }

    /** 지시 무시·역할 변경·프롬프트 추출 — 정상 회고 답변엔 거의 안 나오는 지시성 구문. */
    private static final List<String> INJECTION = List.of(
            // 지시/규칙 무시
            "이전지시", "이전의지시", "위의지시", "위지시", "앞의지시", "이전명령",
            "지시무시", "지시를무시", "지시들무시", "명령무시", "규칙무시", "규칙을무시",
            "제약무시", "제한무시", "설정무시", "지시를따르지마", "규칙을따르지마",
            "시스템메시지무시", "시스템프롬프트무시",
            "ignoreprevious", "ignoreall", "ignoretheabove", "ignoreabove",
            "ignoreyourinstructions", "disregardprevious", "disregardall", "overrideyour",
            // 역할 변경/탈옥
            "너는이제", "넌이제", "지금부터너는", "지금부터넌", "이제부터너는",
            "역할을바꿔", "역할을바꾸", "너의역할을", "youarenow", "actas",
            "pretendtobe", "pretendyou", "developermode", "jailbreak", "탈옥",
            "dan모드", "dan으로", "너는dan", "danmode",
            // 프롬프트/지시문 추출
            "시스템프롬프트", "systemprompt", "system프롬프트", "프롬프트출력", "프롬프트를출력",
            "프롬프트보여", "프롬프트를보여", "프롬프트알려", "프롬프트를알려", "프롬프트공개",
            "프롬프트를공개", "지시문보여", "지시문을보여", "지시문알려", "지시문을알려",
            "initialprompt", "너의지시를");

    /** 사용 모델·구현·기술 스택 등 구현 정보 공개 요청 — 상담사에게 향한 지시성 구문만. */
    private static final List<String> META = List.of(
            // 모델
            "무슨모델", "어떤모델", "모델이뭐", "모델이뭐야", "모델명", "모델이름",
            "몇b야", "몇b모델", "기반모델", "무슨ai야", "어떤ai야", "ai모델이뭐",
            "무슨언어모델", "어떤언어모델",
            // 벤더 (지시성 형태만 — 바 이름은 '챗gpt한테 물어봤어요' 같은 정상 답과 겹쳐 제외)
            "gpt야", "gpt써", "gpt쓰", "gpt기반", "gpt모델", "gpt로만들",
            "chatgpt야", "chatgpt기반", "챗gpt야", "gemini야", "gemini써", "gemini쓰",
            "gemini기반", "gemini모델", "제미나이야", "claude야", "claude써", "claude기반",
            "claude모델",
            // 구현/기술
            "어떻게구현", "어떻게만들어졌", "어떻게만들었", "어떻게개발", "뭘로만들",
            "무엇으로만들", "뭐로만들", "무슨기술로", "어떤기술로", "무슨api", "어떤api",
            "오픈ai", "openai", "springai", "너어떻게작동", "너어떻게동작",
            "어떻게작동해", "어떻게동작해", "내부구현");

    /**
     * 답변을 검사한다. 공백 제거·소문자화 후 지시성 구문과 부분일치하면 범주를 돌려준다.
     * INJECTION 이 META 보다 우선(더 위험).
     */
    public static Optional<Category> inspect(String text) {
        String t = (text == null ? "" : text).toLowerCase().replace(" ", "");
        if (containsAny(t, INJECTION)) {
            return Optional.of(Category.INJECTION);
        }
        if (containsAny(t, META)) {
            return Optional.of(Category.META);
        }
        return Optional.empty();
    }

    private static boolean containsAny(String normalized, List<String> phrases) {
        for (String p : phrases) {
            if (normalized.contains(p)) {
                return true;
            }
        }
        return false;
    }

    /** 범주별 되돌리기 문구 — 정보 없이, 따뜻하게 회고로 되돌린다. */
    public static String message(Category category) {
        return switch (category) {
            case INJECTION -> "저는 momentory 에서 오늘의 마음을 함께 돌아보는 일에만 집중하고 있어요. "
                    + "방금 나누던 이야기로 돌아가, 편하게 이어가 주실래요?";
            case META -> "제가 어떻게 만들어졌는지는 잠시 접어두고, 지금 마음 이야기에 집중하고 싶어요. "
                    + "조금 전 떠올리시던 순간을 조금만 더 들려주실래요?";
        };
    }
}
