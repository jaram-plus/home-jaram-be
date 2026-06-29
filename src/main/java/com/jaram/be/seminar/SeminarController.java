package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import com.jaram.be.security.CurrentMember;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seminars")
public class SeminarController {

    private final SeminarService service;

    public SeminarController(SeminarService service) { this.service = service; }

    @GetMapping
    public List<SeminarResponse> list() { return service.list(); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeminarResponse create(@Valid @RequestBody SeminarCreateRequest req,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.create(req, me.id());
    }
}
