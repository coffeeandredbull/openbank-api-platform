package com.openbank.identity.security;

import com.openbank.identity.auth.JwtIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Temporary RBAC verification endpoints used to prove JWT bearer
 * authentication and role-based authorization. Not business functionality.
 */
@RestController
@RequestMapping("/test")
public class TestAuthController {

    @GetMapping("/authenticated")
    public TestIdentityResponse authenticated(Authentication authentication) {
        return TestIdentityResponse.from(principal(authentication));
    }

    @GetMapping("/admin")
    public TestIdentityResponse admin(Authentication authentication) {
        return TestIdentityResponse.from(principal(authentication));
    }

    private JwtIdentity principal(Authentication authentication) {
        return (JwtIdentity) authentication.getPrincipal();
    }
}