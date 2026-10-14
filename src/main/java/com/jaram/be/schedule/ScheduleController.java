package com.jaram.be.schedule;

import com.jaram.be.schedule.dto.ScheduleResponse;
import com.jaram.be.security.CurrentMember;
import com.jaram.be.seminar.dto.SeminarCreateRequest;
import com.jaram.be.seminar.dto.SeminarResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) { this.service = service; }

    @GetMapping
    public List<ScheduleResponse> list() { return service.list(); }

    @PostMapping("/{id}/slots/{index}/claim")
    public ScheduleResponse claim(@PathVariable String id, @PathVariable int index,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.claim(id, index, me.id());
    }

    @DeleteMapping("/{id}/slots/{index}")
    public ScheduleResponse cancel(@PathVariable String id, @PathVariable int index,
                                   @AuthenticationPrincipal CurrentMember me) {
        return service.cancel(id, index, me.id());
    }

    @PostMapping("/{id}/slots/{index}/seminar")
    @ResponseStatus(HttpStatus.CREATED)
    public SeminarResponse submit(@PathVariable String id, @PathVariable int index,
                                  @Valid @RequestBody SeminarCreateRequest req,
                                  @AuthenticationPrincipal CurrentMember me) {
        return service.submitSeminar(id, index, me.id(), req);
    }
}
