package com.jaram.be.admin;

import java.time.LocalDate;

/**
 * 학년도와 학기(1|2).
 *
 * 학기 경계는 3월 1일과 9월 1일이며 1월 1일이 아니다 — 1~2월은 직전 2학기의
 * 연장이라 학년도를 하나 물린다. 이걸 놓치면 새해 첫날이 학기 경계로 판정되어
 * 회원 전원이 재등록 대상이 된다.
 */
public record Semester(int year, int term) implements Comparable<Semester> {

    public static Semester autoAt(LocalDate on) {
        int month = on.getMonthValue();
        if (month >= 3 && month <= 8) return new Semester(on.getYear(), 1);
        if (month >= 9) return new Semester(on.getYear(), 2);
        return new Semester(on.getYear() - 1, 2);
    }

    /** 이 학기가 시작한 날. 경계를 autoAt 과 같은 곳에 둬야 둘이 어긋나지 않는다. */
    public LocalDate start() {
        return LocalDate.of(year, term == 1 ? 3 : 9, 1);
    }

    @Override
    public int compareTo(Semester o) {
        return year != o.year ? Integer.compare(year, o.year) : Integer.compare(term, o.term);
    }
}
