package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.study.dto.WeekEntry;
import com.jaram.be.study.dto.WeekUpsert;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/studies/{id}/weeks")
public class StudyWeekController {

    private final StudyWeekService service;

    public StudyWeekController(StudyWeekService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public WeekEntry add(@PathVariable String id, @Valid @RequestBody WeekUpsert req) {
        return service.add(id, req);
    }

    @PutMapping("/{weekNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void edit(@PathVariable String id, @PathVariable int weekNo,
                     @Valid @RequestBody WeekUpsert req) {
        service.edit(id, weekNo, req);
    }

    /** 삭제만 편집 창을 본다 — 출석이 딸려 사라지기 때문이다. */
    @DeleteMapping("/{weekNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void remove(@PathVariable String id, @PathVariable int weekNo,
                       @AuthenticationPrincipal CurrentMember me) {
        service.remove(id, weekNo, me.can(Permission.STUDY_EDIT));
    }
}
