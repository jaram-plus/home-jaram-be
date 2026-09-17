package com.jaram.be.study.dto;

/**
 * 계약 StudyApplicantEntry. 관리 모달이 신청자와 스터디원을 같은 한 줄로 그린다.
 *
 * 학번을 싣는다 — 마스킹한 값이다. 이름과 기수만으로는 동명이인이 갈리지 않고,
 * 승인·반려·내보내기는 사람을 잘못 고르면 되돌리기 어려운 동작이다. 마스킹은
 * StudyService.maskStudentId 한 곳에서 하며 상세 명단(RosterEntry)과 같은 규칙이다.
 *
 * approved 묶음에서는 motive 가 null 이다. 승인이 끝난 사람의 지원동기를 계속
 * 보여줄 이유가 없다.
 */
public record StudyApplicantEntry(String applicationId, String studentId, String name,
                                  Integer gen, String motive) { }
