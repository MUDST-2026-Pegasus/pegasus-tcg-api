package com.pegasus.pegasustcgapi.service.user;

import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import com.pegasus.pegasustcgapi.repository.user.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Default implementation of UserVerificationPort [TCG-616].
 */
@Component
public class DefaultUserVerificationPort implements UserVerificationPort {

    private final UserRepository userRepository;

    public DefaultUserVerificationPort(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public boolean isEmailVerified(long userId) {
        return userRepository.findById(userId)
                .map(user -> user.email() != null && !user.email().isBlank())
                .orElse(false);
    }
}
