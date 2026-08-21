package com.momentory.user.withdrawal.application;

import com.momentory.user.application.AuthenticatedUserNotFoundException;
import com.momentory.user.domain.User;
import com.momentory.user.infrastructure.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserDeletionService {

    private final UserRepository userRepository;

    public UserDeletionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void delete(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(AuthenticatedUserNotFoundException::new);
        userRepository.delete(user);
        userRepository.flush();
    }
}
