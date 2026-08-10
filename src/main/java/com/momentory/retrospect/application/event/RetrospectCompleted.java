package com.momentory.retrospect.application.event;

import com.momentory.retrospect.application.ReplyDto;

/**
 * 회고가 정상적으로 끝났고 일기가 나왔다.
 *
 * <p>저장·주간 리포트 집계처럼 뒤에 붙는 부가 관심사는 이 이벤트를 구독하면 된다.
 * 흐름 제어에는 쓰지 않는다.
 */
public record RetrospectCompleted(String sessionId, String mode, ReplyDto.DiaryDto diary) {
}
