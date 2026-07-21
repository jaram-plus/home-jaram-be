package com.jaram.be.member;

import java.time.Year;

/** 기수 계산. 창립 연도 기준 오프셋이며 학번 앞 4자리(입학 연도)에서도 파생한다. */
public final class Gen {

    public static final int FOUNDING_YEAR = 1984;

    private Gen() { }

    public static int current() {
        return Year.now().getValue() - FOUNDING_YEAR;
    }

    /** 학번 앞 4자리 = 대학 입학 연도. 4자리 숫자로 시작하지 않으면 null. */
    public static Integer ofStudentId(String studentId) {
        if (studentId == null || studentId.length() < 4) return null;
        try {
            return Integer.parseInt(studentId.substring(0, 4)) - FOUNDING_YEAR;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
