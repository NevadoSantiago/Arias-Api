package com.arias.auth;

import com.arias.users.Role;
import com.arias.users.User;
import com.arias.users.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea un SUPER_ADMIN inicial si no existe ninguno en la BD.
 *
 * <p>Se ejecuta UNA VEZ al arrancar la app. Idempotente: si ya hay un super admin,
 * no hace nada. Las credenciales se leen de env vars (con defaults de DEV).
 *
 * <p>EN PRODUCCIÓN: setear {@code BOOTSTRAP_ADMIN_EMAIL} y {@code BOOTSTRAP_ADMIN_PASSWORD}
 * en el entorno antes del primer deploy. Después podés cambiar el password desde la app.
 */
@Component
@Slf4j
public class AdminBootstrap implements CommandLineRunner {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String firstName;
    private final String lastName;

    public AdminBootstrap(
        UserRepository userRepo,
        PasswordEncoder passwordEncoder,
        @Value("${arias.bootstrap.admin.email}") String email,
        @Value("${arias.bootstrap.admin.password}") String password,
        @Value("${arias.bootstrap.admin.first-name}") String firstName,
        @Value("${arias.bootstrap.admin.last-name}") String lastName
    ) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
        this.firstName = firstName;
        this.lastName = lastName;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepo.findByEmail(email.toLowerCase()).isPresent()) {
            log.debug("Bootstrap admin ya existe ({}) — skip", email);
            return;
        }

        User admin = User.builder()
            .email(email.toLowerCase())
            .passwordHash(passwordEncoder.encode(password))
            .firstName(firstName)
            .lastName(lastName)
            .role(Role.SUPER_ADMIN)
            .active(true)
            .build();

        userRepo.save(admin);

        log.warn("╔════════════════════════════════════════════════════════╗");
        log.warn("║  SUPER_ADMIN inicial creado                             ║");
        log.warn("║  Email:    {}                                ", email);
        log.warn("║  Password: {} (cambialo en cuanto puedas)    ", password);
        log.warn("╚════════════════════════════════════════════════════════╝");
    }
}
