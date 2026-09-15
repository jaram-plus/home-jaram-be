package com.jaram.be.study.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 계약 WeekUpsert. 주차 추가와 수정이 같은 본문을 쓴다.
 *
 * weekNo 를 받지 않는다. 추가는 언제나 max + 1 이고 수정은 경로가 이미 말한다 —
 * 받으면 본문과 경로가 어긋났을 때 무엇을 믿을지 정해야 한다.
 */
public record WeekUpsert(@NotBlank String title, String content) { }
