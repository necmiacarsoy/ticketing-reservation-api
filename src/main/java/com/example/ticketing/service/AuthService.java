package com.example.ticketing.service;

import java.time.Clock;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.example.ticketing.dto.AuthDtos.LoginRequest;
import com.example.ticketing.dto.AuthDtos.RegisterRequest;
import com.example.ticketing.dto.AuthDtos.TokenResponse;
import com.example.ticketing.dto.AuthDtos.UserResponse;
import com.example.ticketing.entity.User;
import com.example.ticketing.enums.AuditAction;
import com.example.ticketing.enums.Role;
import com.example.ticketing.exception.ApiException;
import com.example.ticketing.repository.UserRepository;
import com.example.ticketing.security.JwtService;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final String RESOURCE = "USER";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuditService audit, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        Role role = request.role() == null ? Role.CUSTOMER : request.role();
        if (role == Role.ADMIN) {
            throw ApiException.forbidden("ADMIN accounts cannot be self-registered");
        }
        String email = normalize(request.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("E-mail is already registered");
        }
        User user = users.saveAndFlush(new User(email, passwordEncoder.encode(request.password()), Set.of(role)));
        audit.record(user.getId(), AuditAction.USER_REGISTERED, RESOURCE, user.getId());
        return UserResponse.from(user);
    }

    public TokenResponse login(LoginRequest request) {
        String email = normalize(request.email());
        Optional<User> found = users.findByEmail(email);
        String hash = found.map(User::getPasswordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (found.isEmpty() || !matches) {
            audit.record(found.map(User::getId).orElse(null), AuditAction.LOGIN_FAILED, RESOURCE,
                    found.map(User::getId).orElse(null));
            throw ApiException.unauthorized("Invalid e-mail or password");
        }
        User user = found.get();
        user.setLastLoginAt(clock.instant());
        users.save(user);
        audit.record(user.getId(), AuditAction.LOGIN_SUCCESS, RESOURCE, user.getId());
        return tokensFor(user);
    }

    public TokenResponse refresh(String refreshToken) {
        Jwt jwt = jwtService.parseRefreshToken(refreshToken);
        Long userId = Long.valueOf(jwt.getSubject());
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired refresh token"));
        audit.record(user.getId(), AuditAction.TOKEN_REFRESHED, RESOURCE, user.getId());
        return tokensFor(user);
    }

    private TokenResponse tokensFor(User user) {
        return new TokenResponse(jwtService.issueAccessToken(user), jwtService.issueRefreshToken(user),
                "Bearer", jwtService.accessTtlSeconds());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
