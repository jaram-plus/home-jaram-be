package com.jaram.be.member;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학기 전환 대상 판정. 휴학·OB·현직 임원은 면제한다 — 한 칸에 값 하나만
 * 들어가므로 "휴학이면서 재등록 필요"를 표현할 수 없다.
 */
class MemberRolloverTargetTest {

    private Member member() {
        Member m = Member.newPending("홍길동", "2023012345", "hong@hanyang.ac.kr", "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        return m;
    }

    @Test
    void activeMemberIsTarget() {
        assertThat(member().isRolloverTarget()).isTrue();
    }

    @Test
    void onLeaveMemberIsExempt() {
        Member m = member();
        m.setStatus(MemberStatus.ON_LEAVE);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    @Test
    void obIsExempt() {
        Member m = member();
        m.setGrade(MemberGrade.OB);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    @Test
    void sittingOfficerIsExempt() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 42);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    /** 임기가 끝난 전 임원은 다시 대상이다 — 이력만 남았을 뿐 현직이 아니다. */
    @Test
    void pastOfficerIsTargetAgain() {
        Member m = member();
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m.endCurrentTerm(42);
        assertThat(m.isRolloverTarget()).isTrue();
    }

    /** 이미 재등록 필요인 회원을 두 번 넘기지 않는다. */
    @Test
    void alreadyRequiredIsNotTargetAgain() {
        Member m = member();
        m.markReregistrationRequired();
        assertThat(m.getStatus()).isEqualTo(MemberStatus.REREGISTER);
        assertThat(m.isRolloverTarget()).isFalse();
    }

    @Test
    void withdrawnIsExempt() {
        Member m = member();
        m.setStatus(MemberStatus.WITHDRAWN);
        assertThat(m.isRolloverTarget()).isFalse();
    }
}
