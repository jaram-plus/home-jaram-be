package com.jaram.be.admin;

import com.jaram.be.common.ClubTime;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
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

    private static final Logger log = LoggerFactory.getLogger(MemberLifecycleService.class);

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

    /**
     * 하루 한 번. 학기 전환이 실제로 일하는 건 1년에 두 번뿐이고 나머지 날은 설정
     * 로우 한 줄을 읽고 끝난다. 주기를 정하는 건 탈퇴 6개월 파기 쪽이다 — 주 1회로
     * 늘리면 파기가 최대 7일 늦어진다.
     *
     * 인스턴스가 여럿이면 같은 날 여러 번 돌 수 있지만 lastRollover 비교가 멱등해
     * 무해하다. 서버가 며칠 꺼져 있었어도 켜질 때 밀린 전환을 따라잡는다.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = ClubTime.ZONE_ID)
    @Transactional   // sweep 을 자기 자신에게서 부르면 프록시를 타지 않는다. 입구에 걸어야 한다
    public void sweepToday() {
        sweep(ClubTime.today());
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
        int purged = 0;
        int keptAsLeader = 0;
        // 파기가 전환보다 먼저다. 그래야 '한 학기를 더 못 넘긴다'가 회원마다 학기를
        // 저장하지 않고도 성립하고, 이번에 넘어간 회원이 같은 스윕에서 지워지지 않는다.
        for (Member m : List.copyOf(members.findAll())) {
            // 이미 파기된 회원은 건너뛴다. Member.purge 는 상태를 건드리지 않으므로
            // 이력이 남은 회원은 파기 뒤에도 REREGISTER 로 남는데, 그냥 두면 학기마다
            // 다시 파기되어 purgedAt('언제 지웠는가')이 계속 밀린다.
            if (m.getStatus() != MemberStatus.REREGISTER || m.getPurgedAt() != null) continue;
            if (purger.purge(m, at) == MemberPurger.Outcome.SKIPPED_LEADER) {
                keptAsLeader++;
                // 이 회원은 REREGISTER 인 채로 남아 신청류가 막히고 승인 탭에도 계속 뜬다.
                // 사람이 스터디 리더를 넘겨야 풀리므로 조용히 지나가면 안 된다.
                log.warn("학기 전환: 스터디 리더라 파기하지 못했습니다. memberId={}", m.getId());
            } else {
                purged++;
            }
        }
        int rolled = 0;
        for (Member m : members.findAll()) {
            if (m.isRolloverTarget()) {
                m.markReregistrationRequired();
                rolled++;
            }
        }
        s.setLastRollover(now);
        log.info("학기 전환 {}-{} → {}-{}: 재등록 대상 {}명, 파기 {}명, 리더로 보류 {}명",
                last.year(), last.term(), now.year(), now.term(), rolled, purged, keptAsLeader);
    }

    private void purgeWithdrawn(LocalDate today) {
        Instant cutoff = atStartOf(today.minusMonths(WITHDRAWAL_RETENTION_MONTHS));
        Instant at = atStartOf(today);
        int purged = 0;
        for (Member m : List.copyOf(members.findAll())) {
            if (m.getStatus() != MemberStatus.WITHDRAWN) continue;
            if (m.getPurgedAt() != null) continue;
            if (m.getWithdrawnAt() == null || m.getWithdrawnAt().isAfter(cutoff)) continue;
            if (purger.purge(m, at) == MemberPurger.Outcome.SKIPPED_LEADER) {
                log.warn("탈퇴 파기: 스터디 리더라 파기하지 못했습니다. memberId={}", m.getId());
            } else {
                purged++;
            }
        }
        // 사람 데이터를 지우는 잡이다. 돌았는지·몇 명이었는지가 남지 않으면 사후 확인이
        // DB 쿼리밖에 없다. 지울 게 없으면 조용하다.
        if (purged > 0) log.info("탈퇴 {}개월 경과 파기: {}명", WITHDRAWAL_RETENTION_MONTHS, purged);
    }

    private static Instant atStartOf(LocalDate d) {
        return ClubTime.startOfDay(d);
    }
}
