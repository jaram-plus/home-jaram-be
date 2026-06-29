package com.jaram.be.seminar;

import com.jaram.be.seminar.dto.SeminarResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/seminars")
public class SeminarController {

    private final SeminarService service;

    public SeminarController(SeminarService service) { this.service = service; }

    @GetMapping
    public List<SeminarResponse> list() { return service.list(); }
}
