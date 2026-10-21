package com.arias.auth;

import com.arias.users.User;
import com.arias.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

    private static final String PASSWORD = "S3cret-bootstrap-pw-9731";

    @Test
    void neverLogsThePasswordWhenCreatingTheAdmin(CapturedOutput output) {
        UserRepository userRepo = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(userRepo.findByEmail("boss@example.com")).thenReturn(Optional.empty());
        when(encoder.encode(PASSWORD)).thenReturn("hashed");

        new AdminBootstrap(userRepo, encoder, "boss@example.com", PASSWORD, "Boss", "Man").run();

        verify(userRepo).save(any(User.class));
        assertThat(output.getAll())
            .contains("boss@example.com")
            .doesNotContain(PASSWORD);
    }
}
