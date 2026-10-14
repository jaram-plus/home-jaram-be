package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.study.dto.WeekEntry;
import com.jaram.be.study.dto.WeekUpsert;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 커리큘럼 주차 편집. 규칙은 설계 D14 가 정했고 여기서는 집행만 한다.
 *
 * 맨 뒤에서만 늘리고 줄인다. 중간 삽입·삭제를 허용하면 뒤 번호를 당길지 정해야 하는데,
 * 당기면 이미 출석이 기록된 "3주차"가 가리키던 모임이 슬그머니 바뀌고, 안 당기면
 * [1,2,4] 같은 구멍이 생겨 화면의 "가장 빠른 빈 주차"가 흔들린다.
 */
@Service
public class StudyWeekService {

    private final StudyRepository studies;
    private final StudyWeekRepository weeks;
    private final StudyAttendanceRepository attendance;
    private final AttendanceWindow window;

    public StudyWeekService(StudyRepository studies, StudyWeekRepository weeks,
                            StudyAttendanceRepository attendance, AttendanceWindow window) {
        this.studies = studies;
        this.weeks = weeks;
        this.attendance = attendance;
        this.window = window;
    }

    @Transactional
    public WeekEntry add(String studyId, WeekUpsert req) {
        Study study = loadStudy(studyId);
        StudyAttendanceService.requireNotFinished(study);

        int next = weeks.findFirstByStudyIdOrderByWeekNoDesc(studyId)
                .map(w -> w.getWeekNo() + 1)
                .orElse(1);
        StudyWeek saved = weeks.save(StudyWeek.create(studyId, next, req.title(), req.content()));
        return new WeekEntry(saved.getWeekNo(), saved.getTitle(), saved.getContent());
    }

    /** 제목·내용은 언제나 고칠 수 있다 — 출석이 기록된 주차도 마찬가지다(D14). */
    @Transactional
    public void edit(String studyId, int weekNo, WeekUpsert req) {
        Study study = loadStudy(studyId);
        StudyAttendanceService.requireNotFinished(study);

        StudyWeek week = loadWeek(studyId, weekNo);
        week.setTitle(req.title());
        week.setContent(req.content());
        weeks.save(week);
    }

    @Transactional
    public void remove(String studyId, int weekNo, boolean officer) {
        Study study = loadStudy(studyId);
        StudyAttendanceService.requireNotFinished(study);

        StudyWeek last = weeks.findFirstByStudyIdOrderByWeekNoDesc(studyId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "그런 주차가 없습니다."));
        if (last.getWeekNo() != weekNo) {
            throw new ApiException(HttpStatus.CONFLICT, "WEEK_NOT_LAST",
                    "맨 마지막 주차만 삭제할 수 있습니다.");
        }
        if (weeks.countByStudyId(studyId) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "WEEK_MIN",
                    "커리큘럼은 최소 1주차가 있어야 합니다.");
        }
        // 출석이 찍힌 주차를 지우는 것은 그 출석을 지우는 것이다 — 같은 창을 쓴다.
        window.requireOpen(last, officer);

        attendance.deleteByWeekId(last.getId());
        weeks.delete(last);
    }

    private Study loadStudy(String id) {
        return studies.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "스터디를 찾을 수 없습니다."));
    }

    private StudyWeek loadWeek(String studyId, int weekNo) {
        return weeks.findByStudyIdAndWeekNo(studyId, weekNo).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "그런 주차가 없습니다."));
    }
}
