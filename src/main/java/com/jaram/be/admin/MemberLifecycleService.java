package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 학기 전환과 파기를 맡는 스윕.
 *
 * 학기 판정은 Semester.autoAt 만 쓴다 — 설정 탭의 학기 override 는 표시와 임기
 * 전환에만 쓰고 여기서는 보지 않는다. 4월에 실수로 2학기를 눌렀다고 사람이
 * 지워지면 안 된다.
 */
@Service
public class MemberLifecycleService {

    private static final int WITHDRAWAL_RETENTION_MONTHS = 6;

    private final MemberRepository members;
    private final AdminSettingsRepository settings;
    private final MemberPurger purger;

    public MemberLifecycleService(MemberRepository members, AdminSettingsRepository settings,
                                  MemberPurger purger) {
        this.members = members;
        this.settings = settings;
        this.purger = purger;
    }

    @Transactional
    void sweep(LocalDate today) {
        rollover(today);
        purgeWithdrawn(today);
    }

    private void rollover(LocalDate today) {
        Semester now = Semester.autoAt(today);
        AdminSettings s = settings.findById(AdminSettings.SINGLETON_ID)
                .orElseGet(() -> settings.save(AdminSettings.defaults()));
        Semester last = s.lastRollover();

        // 마지막 전환 학기를 모르는 것은 '지금 전환해야 한다'는 뜻이 아니다.
        // 초기화만 하고 다음 학기 경계부터 규칙이 돈다.
        if (last == null) {
            s.setLastRollover(now);
            return;
        }
        if (now.compareTo(last) <= 0) return;   // != 가 아니다 — 시계가 뒤로 가도 되돌리지 않는다

        Instant at = atStartOf(today);
        // 파기가 전환보다 먼저다. 그래야 '한 학기를 더 못 넘긴다'가 회원마다 학기를
        // 저장하지 않고도 성립하고, 이번에 넘어간 회원이 같은 스윕에서 지워지지 않는다.
        for (Member m : List.copyOf(members.findAll())) {
            if (m.getStatus() == MemberStatus.REREGISTER) purger.purge(m, at);
        }
        for (Member m : members.findAll()) {
            if (m.isRolloverTarget()) m.markReregistrationRequired();
        }
        s.setLastRollover(now);
    }

    private void purgeWithdrawn(LocalDate today) {
        Instant cutoff = atStartOf(today.minusMonths(WITHDRAWAL_RETENTION_MONTHS));
        Instant at = atStartOf(today);
        for (Member m : List.copyOf(members.findAll())) {
            if (m.getStatus() != MemberStatus.WITHDRAWN) continue;
            if (m.getPurgedAt() != null) continue;
            if (m.getWithdrawnAt() == null || m.getWithdrawnAt().isAfter(cutoff)) continue;
            purger.purge(m, at);
        }
    }

    private static Instant atStartOf(LocalDate d) {
        return d.atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
