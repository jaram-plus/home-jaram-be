package com.jaram.be.study.dto;

import java.util.List;

// 계약 MyActivity. 내 지원 + 내가 개설한 스터디.
public record MyActivity(List<MyApp> apps, List<MyStudy> studies) { }
