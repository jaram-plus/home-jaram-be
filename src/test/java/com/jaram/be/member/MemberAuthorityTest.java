package com.jaram.be.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 권한은 저장하지 않고 직책에서 파생한다. 두 값이 어긋날 수 없다.
class MemberAuthorityTest {

    private Member member() {
        return Member.newPending("김자람", "2023000001", "a@hanyang.ac.kr", "hash");
    }

    @Test
    @DisplayName("직책이 없으면 일반 회원 권한이다")
    void withoutTitleIsMember() {
        assertThat(member().getAuthority()).isEqualTo(Authority.MEMBER);
    }

    @Test
    @DisplayName("직책이 있으면 임원 권한이다")
    void withTitleIsOfficer() {
        Member m = member();
        m.setTitle(MemberTitle.PRESIDENT);
        assertThat(m.getAuthority()).isEqualTo(Authority.OFFICER);
    }

    @Test
    @DisplayName("부원도 임원 권한을 갖는다")
    void staffIsAlsoOfficer() {
        Member m = member();
        m.setTitle(MemberTitle.STAFF);
        assertThat(m.getAuthority()).isEqualTo(Authority.OFFICER);
    }

    @Test
    @DisplayName("직책을 제거하면 권한도 함께 돌아온다")
    void clearingTitleRevokesOfficer() {
        Member m = member();
        m.setTitle(MemberTitle.LEAD);
        m.setTitle(null);
        assertThat(m.getAuthority()).isEqualTo(Authority.MEMBER);
    }
}
