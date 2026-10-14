package com.jaram.be.auth;

import com.jaram.be.auth.dto.LoginRequest;
import com.jaram.be.auth.dto.LoginResponse;
import com.jaram.be.auth.dto.PasswordResetConfirm;
import com.jaram.be.auth.dto.PasswordResetRequest;
import com.jaram.be.auth.dto.SignupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public void signup(@Valid @RequestBody SignupRequest req) {
        auth.signup(req);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/password/reset-request")
    public void resetRequest(@Valid @RequestBody PasswordResetRequest req) {
        auth.requestReset(req);
    }

    @PostMapping("/password/reset")
    public void resetConfirm(@Valid @RequestBody PasswordResetConfirm req) {
        auth.confirmReset(req);
    }
}
