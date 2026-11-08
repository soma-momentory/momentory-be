package com.momentory.letter.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 편지 생성 전용의 작은 실행기 — 지금 서버에 다른 비동기 처리가 없어 편지 몫만 따로 둔다.
 *
 * <p>{@code @Async} 대신 실행기를 직접 부른다. 커밋 뒤(AFTER_COMMIT) 리스너에서 큐가 차 거절되면
 * 그 예외가 이미 커밋된 회고 응답을 500 으로 바꾸기 때문이다. 큐가 차면 버린다 — 할 일은 DB 에
 * PENDING 으로 남아 있어 열 때 보충이 처리한다. 실행기를 빈으로 등록하지 않는 것도 같은 이유다
 * (스프링 부트 기본 실행기와 섞이지 않게).
 */
@Component
public class LetterTaskLauncher {

    private static final Logger log = LoggerFactory.getLogger(LetterTaskLauncher.class);

    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    public LetterTaskLauncher() {
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("letter-");
        executor.initialize();
    }

    public void launch(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("편지 생성 작업 실패(열 때 보충이 이어받음): {}", e.toString());
                }
            });
        } catch (TaskRejectedException e) {
            log.warn("편지 생성 큐가 가득 차 버림(열 때 보충이 이어받음)");
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
