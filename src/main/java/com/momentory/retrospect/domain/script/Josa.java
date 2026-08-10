package com.momentory.retrospect.domain.script;

/**
 * 한국어 조사 선택 유틸 — 값 받침에 맞는 조사를 붙인다.
 *
 * <p>스크립트 템플릿이 일정명·감정어를 끼워 넣을 때 "발표을"처럼 어긋나지 않게 한다.
 * (구 질문은행의 {@code Placeholders} 조사 교정 로직에서 필요한 부분만 남긴 것.)
 */
public final class Josa {

    private Josa() {
    }

    /** word + 을/를 */
    public static String eulReul(String word) {
        return word + pick(word, "을", "를");
    }

    /** word + 이/가 */
    public static String iGa(String word) {
        return word + pick(word, "이", "가");
    }

    /** word + 은/는 */
    public static String eunNeun(String word) {
        return word + pick(word, "은", "는");
    }

    /** word + 과/와 */
    public static String gwaWa(String word) {
        return word + pick(word, "과", "와");
    }

    /** 받침 유무로 조사를 고른다. 마지막 글자가 한글 음절이 아니면 받침 없는 쪽을 쓴다. */
    public static String pick(String word, String withBatchim, String withoutBatchim) {
        if (word == null || word.isEmpty()) {
            return withoutBatchim;
        }
        char last = word.charAt(word.length() - 1);
        if (last < 0xAC00 || last > 0xD7A3) {
            return withoutBatchim;
        }
        int jong = (last - 0xAC00) % 28; // 종성 인덱스. 0 = 받침 없음.
        return jong != 0 ? withBatchim : withoutBatchim;
    }
}
