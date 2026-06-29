package com.jaram.be.auth;

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
}
