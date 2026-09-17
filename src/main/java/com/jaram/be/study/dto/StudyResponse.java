package com.jaram.be.study.dto;

import com.jaram.be.study.ApplyState;
import com.jaram.be.study.StudyStatus;
import java.util.List;

// 계약 Study. leader=이름, leaderGen=기수(미승인 회원이면 null), cur=확정 인원,
// cap=희망 인원(상한이 아니다), apply=사용자별 상태(미인증 null).
public record StudyResponse(
        String id,
        String title,
        List<String> fields,
        String leader,
        Integer leaderGen,
        String intro,
        String schedule,
        String mode,
        int cur,
        int cap,
        StudyStatus status,
        ApplyState apply) {
}
