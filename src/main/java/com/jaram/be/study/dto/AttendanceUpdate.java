package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 계약 AttendanceUpdate. 그 주차의 출석을 통째로 바꾼다.
 *
 * 개별 토글이 아니라 전체 교체인 이유: 체크 해제가 자연스럽게 표현되고, 10명 체크에
 * 요청이 하나라 중간에 끊겨 화면과 서버가 갈라지는 일이 없다.
 *
 * 빈 배열은 "전원 결석"이다. "아직 안 찍음"은 StudyWeek.takenAt 이 null 인 것이다.
 */
public record AttendanceUpdate(@NotNull List<String> present) { }
