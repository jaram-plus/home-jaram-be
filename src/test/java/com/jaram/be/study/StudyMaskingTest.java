package com.jaram.be.study;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 학번은 ^\d{8,10}$ 다. 길이를 보존한 채 앞 4자리와 뒤 1자리만 남긴다. */
class StudyMaskingTest {

    @Test
    void masksTenDigitStudentIds() {
        assertThat(StudyService.maskStudentId("2022123459")).isEqualTo("2022*****9");
    }

    @Test
    void masksEightDigitStudentIds() {
        assertThat(StudyService.maskStudentId("20231234")).isEqualTo("2023***4");
    }

    @Test
    void leavesTooShortOrNullValuesAlone() {
        assertThat(StudyService.maskStudentId(null)).isNull();
        assertThat(StudyService.maskStudentId("12345")).isEqualTo("12345");
    }
}
