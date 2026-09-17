package com.jaram.be.member.dto;

import com.jaram.be.member.MemberCareer;

/**
 * OpenAPI 스키마 MemberCareer 의 와이어 표현. 졸업 후 이력 한 줄이며
 * at 은 'YYYY.MM' 같은 표시용 자유 문자열이다.
 */
public record MemberCareerResponse(String at, String org, String job) {

    public static MemberCareerResponse of(MemberCareer c) {
        return new MemberCareerResponse(c.getAt(), c.getOrg(), c.getJob());
    }
}
