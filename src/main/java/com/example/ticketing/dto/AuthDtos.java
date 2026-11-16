package com.example.ticketing.dto;

import java.time.Instant;
import java.util.Set;

import com.example.ticketing.entity.User;
import com.example.ticketing.enums.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            Role role) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {
    }

    public record UserResponse(Long id, String email, Set<Role> roles, Instant createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), Set.copyOf(user.getRoles()), user.getCreatedAt());
        }
    }
}
