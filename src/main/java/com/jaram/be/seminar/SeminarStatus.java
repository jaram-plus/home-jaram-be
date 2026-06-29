package com.jaram.be.seminar;

import java.time.Instant;

// enum name == JSON wire value (upcoming/ongoing/ended). Server-derived; never stored.
public enum SeminarStatus {
    upcoming, ongoing, ended;

    /**
     * now < startsAt           -> upcoming
     * startsAt <= now <= +win  -> ongoing  (attendance allowed only here)
     * else                     -> ended
     */
    public static SeminarStatus of(Instant startsAt, Instant now, long windowMinutes) {
        if (now.isBefore(startsAt)) {
            return upcoming;
        }
        if (!now.isAfter(startsAt.plusSeconds(windowMinutes * 60))) {
            return ongoing;
        }
        return ended;
    }
}
