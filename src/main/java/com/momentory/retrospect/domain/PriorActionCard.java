package com.momentory.retrospect.domain;

import java.time.LocalDate;

/**
 * 사용자가 전에 만든 행동 카드 한 장 — "비슷한 상황"에서 추천 첫 줄로 되살리는 값이다.
 *
 * @param action      그때 정한 목표 행동(옵션의 label 이 된다)
 * @param detail      한 줄 설명(옵션의 description, 없으면 null)
 * @param situation   그때의 상황 — 화면 배지("지난 …에서") 용
 * @param createdDate 그때 만든 날 — 화면 배지 용
 */
public record PriorActionCard(String action, String detail, String situation,
        LocalDate createdDate) {
}
