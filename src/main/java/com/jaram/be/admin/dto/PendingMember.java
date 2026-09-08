package com.jaram.be.admin.dto;

/**
 * '가입 신청·승인' 화면의 한 줄. 가입 승인 대기(SIGNUP)와 재등록 필요(REREGISTER)가
 * 한 목록에 섞이므로 kind 로 구분한다. requestedAt 은 재등록 신청 시각이며,
 * 아직 신청하지 않았거나 가입 대기면 null 이다.
 */
public record PendingMember(String id, String name, String studentId, String email,
                            String createdAt, String kind, String requestedAt) { }
