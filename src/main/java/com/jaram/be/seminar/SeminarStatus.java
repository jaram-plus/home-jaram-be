package com.jaram.be.seminar;

import java.time.Instant;

// enum name == JSON wire value (UPCOMING/ONGOING/ENDED). Server-derived; never stored.
public enum SeminarStatus {
    UPCOMING, ONGOING, ENDED;

    /**
     * now < startsAt           -> UPCOMING
     * startsAt <= now <= +win  -> ONGOING  (attendance allowed only here)
     * else                     -> ENDED
     */
    public static SeminarStatus of(Instant startsAt, Instant now, long windowMinutes) {
        if (now.isBefore(startsAt)) {
            return UPCOMING;
        }
        if (!now.isAfter(startsAt.plusSeconds(windowMinutes * 60))) {
            return ONGOING;
        }
        return ENDED;
    }
}
