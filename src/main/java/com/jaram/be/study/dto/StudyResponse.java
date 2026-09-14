package com.jaram.be.study.dto;

import com.jaram.be.study.ApplyState;
import com.jaram.be.study.StudyStatus;
import java.util.List;

// 계약 Study. leader=이름, cur=현재 인원(파생), cap=정원, apply=사용자별 상태(미인증 null).
public record StudyResponse(
        String id,
        String title,
        List<String> fields,
        String leader,
        String schedule,
        String period,
        String mode,
        int cur,
        int cap,
        StudyStatus status,
        ApplyState apply) {
}
