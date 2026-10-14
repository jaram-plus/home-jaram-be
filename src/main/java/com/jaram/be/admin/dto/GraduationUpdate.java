package com.jaram.be.admin.dto;

import java.util.List;

/**
 * 계약 GraduationUpdate. 졸업생 상세에서 고치는 것 — 졸업연도와 졸업 후 이력뿐이다.
 * careers 는 보낸 목록으로 통째로 교체한다(빠진 줄은 지워진다).
 */
public record GraduationUpdate(Integer gradYear, List<Career> careers) {

    public record Career(String at, String org, String job) { }
}
