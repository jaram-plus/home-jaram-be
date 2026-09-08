package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 학기 전환과 파기를 맡는 스윕.
 *
 * 학기 판정은 Semester.autoAt 만 쓴다 — 설정 탭의 학기 override 는 표시와 임기
 * 전환에만 쓰고 여기서는 보지 않는다. 4월에 실수로 2학기를 눌렀다고 사람이
 * 지워지면 안 된다.
 */
@Service
public class MemberLifecycleService {

    private final MemberRepository members;
    private final AdminSettingsRepository settings;

    public MemberLifecycleService(MemberRepository members, AdminSettingsRepository settings) {
        this.members = members;
        this.settings = settings;
    }

    @Transactional
    void sweep(LocalDate today) {
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

        for (Member m : members.findAll()) {
            if (m.isRolloverTarget()) m.markReregistrationRequired();
        }
        s.setLastRollover(now);
    }
}
