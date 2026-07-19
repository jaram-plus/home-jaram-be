package com.jaram.be.schedule;

import com.jaram.be.schedule.dto.ScheduleCreateRequest;
import com.jaram.be.schedule.dto.ScheduleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/schedules")
public class AdminScheduleController {

    private final ScheduleService service;

    public AdminScheduleController(ScheduleService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleResponse create(@Valid @RequestBody ScheduleCreateRequest req) {
        return service.create(req);
    }

    @PatchMapping("/{id}/lock")
    public ScheduleResponse lock(@PathVariable String id) {
        return service.lock(id);
    }

    @DeleteMapping("/{id}/slots/{index}")
    public ScheduleResponse forceRelease(@PathVariable String id, @PathVariable int index) {
        return service.forceRelease(id, index);
    }
}
