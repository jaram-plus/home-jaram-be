package com.jaram.be.me.dto;

import com.jaram.be.member.Authority;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTitle;

public record MeProfile(
        String id,
        String name,
        String studentId,
        String email,
        Authority authority,
        MemberDepartment department,  // enum name, nullable
        MemberTitle title,            // enum name, nullable
        String gen,
        String bio,
        String githubUrl,
        String blogUrl) {
}
