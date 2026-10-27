package com.momentory.retrospect.application;

import java.util.Optional;

import com.momentory.retrospect.domain.TodayAction;

/**
 * 「오늘 해 보기로 한 작은 일」을 찾는 포트 — retrospect 가 정의하고 letter 컨텍스트가 구현한다
 * ({@link PriorActionCardRecommender} 와 같은 결). 회고는 편지를 어떻게 저장하는지 모른다.
 * 회고를 시작할 때 한 번 부르고, 오늘 받은 편지에서 고른 일이 없으면 {@link Optional#empty()}.
 */
public interface TodayActionFinder {

    Optional<TodayAction> findFor(Long userId);
}
