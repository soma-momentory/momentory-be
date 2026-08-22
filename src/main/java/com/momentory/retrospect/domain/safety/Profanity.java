package com.momentory.retrospect.domain.safety;

import java.util.List;

/**
 * 욕설 어휘·정규화 헬퍼 — 규칙 층 어뷰징 판정({@link AbuseGate})의 사전이다.
 *
 * <p><b>{@link SafetyPolicy} 와 분리한 이유.</b> SafetyPolicy 의 욕설 목록은 원본 safety.py 를
 * 그대로 옮긴 임상 검수 전 시드라 "목록 교체는 임상 검수 후"라는 제약이 걸려 있다. 거기는 욕설을
 * <u>차단하지 않고</u> CAUTION 플래그만 달아 AI 가 부드럽게 받게 하는 신호축이다 — 감정 회고에서
 * 욕은 대개 정당한 감정 표출이기 때문이다. 여기 사전은 그와 목적이 다르다: <b>반영할 회고 내용이
 * 0 인 입력</b>(욕만 있는 한마디)과 <b>상담사를 향한 공격</b>만 결정적으로 걸러 AI 호출을 아낀다.
 *
 * <p><b>정밀도 우선.</b> 완벽한 욕설 사전을 만들려 하지 않는다 — 그건 지는 군비경쟁이고, 놓친 표현은
 * SafetyPolicy 의 CAUTION 과 시스템 프롬프트 하드닝이 백업으로 받는다. 그래서 정상 회고 문장과
 * 겹치는 애매한 단어(예: 단독 "미친" → "미친 하루였어")는 사전에서 뺐다.
 */
public final class Profanity {

    private Profanity() {
    }

    /**
     * 감정 표출형 욕설(자기 지향) — 정규화 형태로 저장한다. 단독으로 쓰이면 회고 내용이 없다는
     * 신호일 뿐, 그 자체로 공격은 아니다. 정상 회고 문장과 겹치지 않는 고신뢰 표현만 둔다.
     */
    private static final List<String> EXPLETIVES = List.of(
            "씨발", "시발", "씨빨", "시빨", "씨바", "시바", "씨팔", "시팔", "씨불", "ㅅㅂ", "ㅆㅂ",
            "존나", "존내", "ㅈㄴ", "좆", "좇", "좆같", "병신", "ㅂㅅ", "지랄", "ㅈㄹ",
            "개새끼", "개새", "개색", "개세끼", "썅", "엿같", "미친놈", "미친년", "등신", "개소리");

    /**
     * 상담사를 향한 명령형 공격 — 듣는 이를 전제로만 성립해 정상 회고엔 거의 안 나온다. 2인칭
     * 대명사("너"/"니") 부분일치는 "너무"·"어머니" 같은 정상 단어와 충돌해 오탐이 크므로 쓰지 않고,
     * 충돌 없는 명령형 표현만 둔다. "죽어"는 위기 표현과 겹쳐 일부러 뺐다(SafetyPolicy 가 먼저 본다).
     */
    private static final List<String> DIRECTED = List.of(
            "닥쳐", "닥치", "ㄷㅊ", "꺼져", "꺼저", "ㄲㅈ", "좆까", "엿먹어", "개소리하지마");

    /** 욕설을 걷어낸 뒤 남을 수 있는 감탄사·군더더기 — 이것만 남으면 실질 내용이 없다고 본다. */
    private static final List<String> FILLERS = List.of(
            "아", "악", "으", "윽", "흑", "흐", "하", "휴", "후", "헐", "와", "웅", "음", "엥",
            "헉", "쳇", "칫", "에이", "아이", "아오", "아우", "야", "어", "엌", "우");

    /**
     * 욕설 판정용 정규화 — 소문자화 후 한글 음절·자음(ㄱ~ㅎ)·영숫자만 남긴다.
     * 공백·문장부호·삽입문자("시.발"·"씨*발")·모음 노이즈("ㅠㅠ")를 지워 우회를 무디게 한다.
     */
    public static String normalize(String text) {
        return (text == null ? "" : text).toLowerCase().replaceAll("[^가-힣ㄱ-ㅎ0-9a-z]", "");
    }

    /** 상담사를 향한 명령형 공격이 섞여 있는가 — 회고로 되돌릴(deflect) 대상. */
    public static boolean isDirectedAbuse(String text) {
        String norm = normalize(text);
        return containsAny(norm, DIRECTED);
    }

    /**
     * 욕설을 걷어내면 실질 내용이 남지 않는가 — "씨발"·"아 씨발"·"ㅅㅂㅅㅂ"처럼 감정만 왔고
     * 반영할 회고 내용이 0 인 경우. 이때는 AI 를 불러도 되비출 게 없어 따뜻한 스캐폴드로 되묻는다.
     * 반대로 "씨발 그 사람 때문에 힘들었어"는 욕을 빼도 내용이 남아 {@code false}(= AI 로 통과).
     */
    public static boolean isOnlyProfanity(String text) {
        String norm = normalize(text);
        if (!containsAny(norm, EXPLETIVES) && !containsAny(norm, DIRECTED)) {
            return false;
        }
        String rest = norm;
        for (String p : EXPLETIVES) {
            rest = rest.replace(p, "");
        }
        for (String p : DIRECTED) {
            rest = rest.replace(p, "");
        }
        // 남은 자음 노이즈(ㅋㅋ)·숫자를 지우고, 감탄사 군더더기를 뺀 뒤 실질 내용이 있는지 본다.
        rest = rest.replaceAll("[ㄱ-ㅎ0-9]", "");
        for (String f : FILLERS) {
            rest = rest.replace(f, "");
        }
        return rest.isBlank();
    }

    private static boolean containsAny(String normalized, List<String> phrases) {
        for (String p : phrases) {
            if (normalized.contains(p)) {
                return true;
            }
        }
        return false;
    }
}
