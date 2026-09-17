package com.jaram.be.study.dto;

import com.jaram.be.study.ApplyState;
import com.jaram.be.study.StudyStatus;
import java.util.List;

// 계약 StudyDetail. 목록 카드가 가진 것에 장소·문의·커리큘럼·지원 인원이 더해진다.
public record StudyDetail(
        String id,
        String title,
        List<String> fields,
        String intro,
        String leader,
        Integer leaderGen,
        String schedule,
        String place,
        String mode,
        String contact,
        int cur,
        int cap,
        StudyStatus status,
        ApplyState apply,
        List<WeekEntry> weeks,
        List<RosterEntry> roster) {
}
