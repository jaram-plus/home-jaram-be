package com.jaram.be.me;

import com.jaram.be.me.dto.MeProfile;
import com.jaram.be.me.dto.MeUpdateRequest;
import com.jaram.be.security.CurrentMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/me")
public class MeController {

    private final MeService service;

    public MeController(MeService service) { this.service = service; }

    @GetMapping
    public MeProfile get(@AuthenticationPrincipal CurrentMember me) {
        return service.get(me.id());
    }

    @PatchMapping
    public MeProfile update(@Valid @RequestBody MeUpdateRequest req,
                            @AuthenticationPrincipal CurrentMember me) {
        return service.update(me.id(), req);
    }

    @PostMapping("/reregister")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reregister(@AuthenticationPrincipal CurrentMember me) {
        service.requestReregistration(me.id());
    }

    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal CurrentMember me) {
        service.withdraw(me.id());
    }
}
