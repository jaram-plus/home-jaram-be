package com.jaram.be.study;

/**
 * 계약 AttendanceState.
 *
 * NOT_TAKEN 을 따로 두는 이유: 화면이 takenAt 의 유무로 세 번째 상태를 유추하게 두면
 * 매번 같은 실수를 부른다 — 아직 안 찍은 주차가 결석으로 보인다.
 */
public enum AttendanceState { PRESENT, ABSENT, NOT_TAKEN }
