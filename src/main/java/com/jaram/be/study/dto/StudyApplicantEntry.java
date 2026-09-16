package com.jaram.be.study.dto;

/**
 * 계약 StudyApplicantEntry. 스터디장이 신청을 가려내는 데 쓰는 것은 이름·기수·동기다.
 *
 * 학번을 싣지 않는다 — 상세 모달의 명단(① §7)은 지원자 전체를 보이므로 마스킹이
 * 필요했지만, 여기는 애초에 싣지 않는 편이 짧다.
 *
 * approved 묶음에서는 motive 가 null 이다. 승인이 끝난 사람의 지원동기를 계속
 * 보여줄 이유가 없다.
 */
public record StudyApplicantEntry(String applicationId, String name, Integer gen, String motive) { }
