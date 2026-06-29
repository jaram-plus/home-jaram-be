package com.jaram.be.people;

import com.jaram.be.people.dto.PeopleResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/people")
public class PeopleController {

    private final PeopleService service;

    public PeopleController(PeopleService service) { this.service = service; }

    @GetMapping
    public PeopleResponse list() { return service.list(); }
}
