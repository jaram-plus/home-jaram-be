package com.jaram.be.member.dto;

import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberTerm;
import com.jaram.be.member.MemberTitle;

/**
 * OpenAPI 스키마 MemberTerm 의 와이어 표현. 임기 안에서는 department·title 이 항상
 * 있고, endGen 이 null 이면 현직이다. me·people 양쪽이 쓰므로 공용 member 패키지에 둔다.
 */
public record MemberTermResponse(
        MemberDepartment department,
        MemberTitle title,
        int startGen,
        Integer endGen) {

    public static MemberTermResponse of(MemberTerm t) {
        return new MemberTermResponse(t.getDepartment(), t.getTitle(), t.getStartGen(), t.getEndGen());
    }
}
