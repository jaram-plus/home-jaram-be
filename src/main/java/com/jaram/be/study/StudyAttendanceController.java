package com.jaram.be.study;

import com.jaram.be.security.CurrentMember;
import com.jaram.be.security.authz.Permission;
import com.jaram.be.study.dto.AttendanceBoard;
import com.jaram.be.study.dto.AttendanceUpdate;
import com.jaram.be.study.dto.MyAttendance;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/studies/{id}")
public class StudyAttendanceController {

    private final StudyAttendanceService service;

    public StudyAttendanceController(StudyAttendanceService service) { this.service = service; }

    @GetMapping("/attendance")
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public AttendanceBoard board(@PathVariable String id,
                                 @AuthenticationPrincipal CurrentMember me) {
        return service.board(id, me.can(Permission.STUDY_EDIT));
    }

    /**
     * 자기 출석. 게이트가 isMember 다 — 같은 스터디 사람만 본다.
     *
     * /attendance/me 가 /attendance 보다 먼저 선언될 필요는 없다. Spring 은 리터럴
     * 세그먼트를 경로 변수보다 먼저 맞추고, 여기에는 겹치는 경로 변수가 없다.
     */
    @GetMapping("/attendance/me")
    @PreAuthorize("@studyAccess.isMember(#id, authentication)")
    public MyAttendance mine(@PathVariable String id,
                             @AuthenticationPrincipal CurrentMember me) {
        return service.mine(id, me.id());
    }

    /**
     * 그 주차의 출석을 통째로 바꾼다.
     *
     * 게이트는 "스터디장 또는 임원"만 가른다. 편집 창은 시간의 문제라 권한 표현식으로
     * 쓸 수 없으므로 서비스가 본다 — 임원인지를 여기서 읽어 넘긴다. 서비스가
     * SecurityContextHolder 를 직접 읽으면 서비스 테스트가 보안 컨텍스트를 세워야 한다.
     */
    @PutMapping("/weeks/{weekNo}/attendance")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@studyAccess.isLeader(#id, authentication) or hasAuthority('STUDY_EDIT')")
    public void saveAttendance(@PathVariable String id,
                               @PathVariable int weekNo,
                               @Valid @RequestBody AttendanceUpdate req,
                               @AuthenticationPrincipal CurrentMember me) {
        service.save(id, weekNo, req.present(), me.can(Permission.STUDY_EDIT));
    }
}
