package com.jaram.be.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 학회가 쓰는 시간대.
 *
 * 학기·기수·학기 전환 스윕이 모두 '오늘이 며칠인가'로 판정하는데, 컨테이너 기본
 * 시간대는 UTC 라 그냥 두면 KST 자정부터 아홉 시간 동안 날짜가 하루 뒤진다. 그
 * 구간에 3월 1일이나 1월 1일이 걸리면 학기와 기수가 하루 늦게 넘어가고, 스케줄러의
 * '새벽 4시'는 실제로 오후 1시가 된다.
 *
 * 배포 환경의 TZ 설정에 기대지 않도록 코드에 못 박는다.
 */
public final class ClubTime {

    /** @Scheduled(zone = ...) 이 컴파일 상수를 요구해서 문자열로도 둔다. */
    public static final String ZONE_ID = "Asia/Seoul";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private ClubTime() { }

    /** 학회 기준 오늘. */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** 그 날 자정(학회 기준). 날짜 비교를 Instant 칸에 맞출 때 쓴다. */
    public static Instant startOfDay(LocalDate d) {
        return d.atStartOfDay(ZONE).toInstant();
    }
}
