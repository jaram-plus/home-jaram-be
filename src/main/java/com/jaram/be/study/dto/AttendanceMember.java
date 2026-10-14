package com.jaram.be.study.dto;

import java.util.List;

/** 계약 AttendanceMember. 학번을 싣지 않는다 — 이름과 기수면 격자가 성립한다. */
public record AttendanceMember(String memberId, String name, Integer gen,
                               boolean leader, List<Integer> present) { }
