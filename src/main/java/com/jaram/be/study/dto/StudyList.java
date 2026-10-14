package com.jaram.be.study.dto;

import java.util.List;

/**
 * 계약 StudyList. 목록을 감싸 모집 토글 상태를 함께 싣는다.
 *
 * 토글은 임원 전용 설정이 아니라 스터디 페이지가 첫 화면에서 알아야 하는 값이다.
 * 전용 GET 을 새로 내는 대신 어차피 부르는 이 응답에 얹는다.
 */
public record StudyList(boolean recruiting, List<StudyResponse> items) {
}
