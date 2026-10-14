package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 편집 창 — 규칙 하나가 출석 저장과 주차 삭제를 다 덮는다.
 *
 * 스터디장은 아직 안 찍은 주차이거나 첫 저장으로부터 24시간 안일 때만 그 주차를
 * 건드린다. 두 곳에 같은 조건문을 쓰면 한쪽만 고쳐지는 날이 오므로 여기 한 곳에 둔다.
 *
 * 임원(STUDY_EDIT)은 창을 무시한다. 창 판정에 권한 조회를 넣지 않고 boolean 으로
 * 받는 이유는, 이 클래스가 SecurityContext 를 알면 단위 테스트가 보안 컨텍스트를
 * 세워야 하기 때문이다. 부르는 쪽(컨트롤러)이 STUDY_EDIT 보유 여부를 넘긴다.
 */
@Component
public class AttendanceWindow {

    public static final Duration WINDOW = Duration.ofHours(24);

    public boolean isOpen(StudyWeek week, boolean officer, Instant now) {
        if (officer) return true;
        Instant taken = week.getTakenAt();
        if (taken == null) return true;
        return now.isBefore(taken.plus(WINDOW));
    }

    /**
     * 409 다. 403 이 아니다 — 스터디장은 이 주차에 대한 권한을 갖고 있고 시간이
     * 지났을 뿐이다. 403 으로 내면 화면이 "권한이 없습니다"를 띄우고, 그것은 틀린
     * 설명이라 사용자가 관리자에게 권한을 요청하게 만든다.
     */
    public void requireOpen(StudyWeek week, boolean officer) {
        if (isOpen(week, officer, Instant.now())) return;
        throw new ApiException(HttpStatus.CONFLICT, "ATTENDANCE_LOCKED",
                "출석 수정 기간이 지났습니다. 임원에게 요청하세요.");
    }
}
