package com.jaram.be.schedule.dto;

/**
 * 슬롯을 맡은 회원. gen 은 기수 정수이고 "기" 는 FE 가 붙인다(MeService 와 같은 규약).
 * 미설정이면 null — 승인 전 회원에게는 기수가 없다.
 */
public record SlotMember(String id, String name, Integer gen) { }
