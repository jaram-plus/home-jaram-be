package com.jaram.be.admin;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄 배선은 스프링이 뜬 뒤에야 도는 코드라 통합 테스트로 잡기 어렵다.
 * 애노테이션이 붙어 있는지, 주기가 하루인지만 여기서 못 박는다.
 */
class MemberLifecycleScheduleTest {

    @Test
    void sweepRunsOnceADay() throws NoSuchMethodException {
        Method m = MemberLifecycleService.class.getMethod("sweepToday");
        Scheduled scheduled = m.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 4 * * *");
    }
}
