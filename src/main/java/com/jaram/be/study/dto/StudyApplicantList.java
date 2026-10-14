package com.jaram.be.study.dto;

import java.util.List;

/** 계약 StudyApplicantList. 모달이 두 묶음으로 그리므로 두 묶음으로 내려보낸다. */
public record StudyApplicantList(List<StudyApplicantEntry> pending,
                                 List<StudyApplicantEntry> approved) { }
