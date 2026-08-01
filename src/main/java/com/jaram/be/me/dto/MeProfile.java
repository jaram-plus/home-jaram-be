package com.jaram.be.me.dto;

import com.jaram.be.member.Authority;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;

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
        Integer gen,
        String bio,
        String githubUrl,
        String blogUrl) {
}
