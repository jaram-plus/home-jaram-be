package com.jaram.be.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 라벨 파생과 조합 규칙은 MemberTitle 한 곳에만 둔다. 이 테스트가 그 계약이다.
class MemberTitleTest {

    @Test
    @DisplayName("부서 라벨에 직위 접미사를 붙여 표시 라벨을 만든다")
    void composesDepartmentLabelWithTitleSuffix() {
        assertThat(MemberTitle.LEAD.label(MemberDepartment.ACADEMIC)).isEqualTo("학술부장");
        assertThat(MemberTitle.STAFF.label(MemberDepartment.ACADEMIC)).isEqualTo("학술부원");
        assertThat(MemberTitle.LEAD.label(MemberDepartment.PR)).isEqualTo("홍보부장");
        assertThat(MemberTitle.STAFF.label(MemberDepartment.PR)).isEqualTo("홍보부원");
        assertThat(MemberTitle.LEAD.label(MemberDepartment.FINANCE)).isEqualTo("회계부장");
        assertThat(MemberTitle.STAFF.label(MemberDepartment.FINANCE)).isEqualTo("회계부원");
    }

    @Test
    @DisplayName("부서와 무관하게 고정 라벨을 갖는 직책이 있다")
    void absoluteTitlesIgnoreDepartment() {
        assertThat(MemberTitle.PRESIDENT.label(MemberDepartment.LEADERSHIP)).isEqualTo("회장");
        assertThat(MemberTitle.VICE_PRESIDENT.label(MemberDepartment.LEADERSHIP)).isEqualTo("부회장");
        assertThat(MemberTitle.SERVER_ADMIN.label(MemberDepartment.INFRA)).isEqualTo("서버 관리자");
        assertThat(MemberTitle.PRESIDENT.label(null)).isEqualTo("회장");
        assertThat(MemberTitle.SERVER_ADMIN.label(null)).isEqualTo("서버 관리자");
    }

    @Test
    @DisplayName("부서가 없으면 접미사 규칙은 부장/부원으로 폴백한다")
    void fallsBackWhenDepartmentMissing() {
        assertThat(MemberTitle.LEAD.label(null)).isEqualTo("부장");
        assertThat(MemberTitle.STAFF.label(null)).isEqualTo("부원");
    }

    @Test
    @DisplayName("부서마다 지정할 수 있는 직책이 정해져 있다")
    void allowsOnlyTitlesThatFitTheDepartment() {
        assertThat(MemberTitle.PRESIDENT.allowedIn(MemberDepartment.LEADERSHIP)).isTrue();
        assertThat(MemberTitle.VICE_PRESIDENT.allowedIn(MemberDepartment.LEADERSHIP)).isTrue();
        assertThat(MemberTitle.LEAD.allowedIn(MemberDepartment.LEADERSHIP)).isFalse();

        assertThat(MemberTitle.LEAD.allowedIn(MemberDepartment.ACADEMIC)).isTrue();
        assertThat(MemberTitle.STAFF.allowedIn(MemberDepartment.FINANCE)).isTrue();
        assertThat(MemberTitle.PRESIDENT.allowedIn(MemberDepartment.PR)).isFalse();
        assertThat(MemberTitle.SERVER_ADMIN.allowedIn(MemberDepartment.ACADEMIC)).isFalse();

        assertThat(MemberTitle.SERVER_ADMIN.allowedIn(MemberDepartment.INFRA)).isTrue();
        assertThat(MemberTitle.LEAD.allowedIn(MemberDepartment.INFRA)).isFalse();
    }

    @Test
    @DisplayName("부서가 없으면 어떤 직책도 지정할 수 없다")
    void noTitleFitsAMissingDepartment() {
        for (MemberTitle t : MemberTitle.values()) {
            assertThat(t.allowedIn(null)).isFalse();
        }
    }
}
