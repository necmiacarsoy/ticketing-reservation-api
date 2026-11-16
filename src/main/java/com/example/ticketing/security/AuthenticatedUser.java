package com.example.ticketing.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.ticketing.enums.Role;

import org.springframework.security.oauth2.jwt.Jwt;

public record AuthenticatedUser(Long id, Set<Role> roles) {

    public static AuthenticatedUser from(Jwt jwt) {
        List<String> claim = jwt.getClaimAsStringList(JwtService.CLAIM_ROLES);
        Set<Role> roles = claim == null
                ? Set.of()
                : claim.stream().map(Role::valueOf).collect(Collectors.toUnmodifiableSet());
        return new AuthenticatedUser(Long.valueOf(jwt.getSubject()), roles);
    }

    public boolean isAdmin() {
        return roles.contains(Role.ADMIN);
    }

    public boolean owns(Long ownerId) {
        return id.equals(ownerId);
    }
}
