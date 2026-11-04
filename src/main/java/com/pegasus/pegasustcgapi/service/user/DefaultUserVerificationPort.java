package com.pegasus.pegasustcgapi.service.user;

import com.pegasus.pegasustcgapi.port.UserVerificationPort;
import org.springframework.stereotype.Component;

/**
 * Default implementation of UserVerificationPort [TCG-616].
 */
@Component
public class DefaultUserVerificationPort implements UserVerificationPort {

    @Override
    public boolean isEmailVerified(long userId) {
        return true;
    }
}
