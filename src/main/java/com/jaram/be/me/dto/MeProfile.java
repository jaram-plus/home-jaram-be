package com.jaram.be.me.dto;

import com.jaram.be.member.Authority;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.member.dto.MemberTermResponse;

import java.util.List;

public record MeProfile(
        String id,
        String name,
        String studentId,
        String email,
        Authority authority,
        MemberGrade grade,            // enum name, nullable (승인 전)
        MemberStatus status,          // 활동축 enum name
        MemberDepartment department,  // enum name, nullable
        MemberTitle title,            // enum name, nullable
        List<MemberTermResponse> terms,   // 임기 이력(오래된 순), 없으면 빈 배열
        Integer gen,
        String faculty,               // 읽기 전용 — MeUpdateRequest 에는 없다
        String phone,
        boolean contributor,
        String bio,
        String githubUrl,
        String blogUrl) {
}
