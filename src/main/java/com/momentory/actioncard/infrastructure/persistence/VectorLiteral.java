package com.momentory.actioncard.infrastructure.persistence;

/**
 * pgvector 리터럴 포맷팅 — {@code float[]} → {@code "[0.1,0.2,...]"}.
 *
 * <p>JPA 는 vector 컬럼을 매핑하지 않아 네이티브 SQL 의 {@code CAST(:vec AS vector)} 로 넣는데,
 * 그 문자열 표현을 만드는 곳이 유사도 조회·임베딩 저장 두 곳이라 여기로 모은다.
 */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    public static String of(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
