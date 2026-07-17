package com.hirex.config;

import com.hirex.entity.Role;
import com.hirex.entity.User;
import com.hirex.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds a default admin user on first startup (only if no user exists with
 * that email yet — safe to leave running across restarts/redeploys).
 *
 * SECURITY FIX: previously hardcoded "admin123" as the password for a
 * well-known email (admin@hirex.com) and printed the credentials to stdout
 * on every deploy — a textbook default-credential vulnerability if anyone
 * ever deploys before rotating it. Now:
 *   - Password comes from ADMIN_DEFAULT_PASSWORD (set it in Render's
 *     Environment tab before first deploy to a fresh database).
 *   - If that env var isn't set, a random one is generated and logged
 *     ONCE at startup (not a fixed, guessable value) so the deploy still
 *     succeeds but nobody can rely on a known default.
 *   - Credentials are logged via the logger (which you control/redact in
 *     your log sink), not System.out.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.admin.default-email:Admin@gmail.com}")
    private String adminEmail;

    @Value("${app.admin.default-password:}")
    private String configuredAdminPassword;

    public DataSeeder(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (userRepo.existsByEmail(adminEmail)) {
            return;
        }

        String plainPassword = (configuredAdminPassword != null && !configuredAdminPassword.isBlank())
                ? configuredAdminPassword
                : java.util.UUID.randomUUID().toString();

        User admin = new User();
        admin.setName("Admin");
        admin.setEmail(adminEmail);
        admin.setPassword(passwordEncoder.encode(plainPassword));
        admin.setRole(Role.ADMIN);
        admin.setPhone("0000000000");
        userRepo.save(admin);

        if (configuredAdminPassword == null || configuredAdminPassword.isBlank()) {
            log.warn("No ADMIN_DEFAULT_PASSWORD set — generated a random one-time admin " +
                    "password for {}. Set ADMIN_DEFAULT_PASSWORD in your environment and " +
                    "re-seed if you need a known value: {}", adminEmail, plainPassword);
        } else {
            log.info("Default admin account ensured for {}.", adminEmail);
        }
    }
}
