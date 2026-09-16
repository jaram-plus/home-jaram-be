package com.jaram.be.study.dto;

/**
 * 계약 StudyRosterEntry. 상세 모달 하단의 지원 인원 한 줄.
 *
 * 승인 여부를 내려보내지 않는다 — 객체에 상태 필드 자체를 넣지 않는다. 쓰지 않는
 * 필드를 실어 보내면 언젠가 화면에 샌다. studentId 는 서버가 마스킹한 값이다.
 */
public record RosterEntry(String studentId, Integer gen, String name) {
}
