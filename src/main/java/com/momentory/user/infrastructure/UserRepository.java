package com.momentory.user.infrastructure;

import com.momentory.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    /** AI 완료와 직접 쓰기가 동시에 일기를 만들 때 사용자별로 직렬화한다. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.id = :id")
    java.util.Optional<User> findForDiaryCreation(Long id);
}
