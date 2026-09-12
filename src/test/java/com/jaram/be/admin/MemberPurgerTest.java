package com.jaram.be.admin;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyRepository;
import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MemberPurgerTest extends PostgresTest {

    private static final Instant AT = Instant.parse("2027-03-01T00:00:00Z");

    @Autowired MemberPurger purger;
    @Autowired MemberRepository members;
    @Autowired StudyRepository studies;

    @BeforeEach void setup() {
        studies.deleteAll();
        members.deleteAll();
    }

    private Member saved(String name, String studentId, String email) {
        Member m = Member.newPending(name, studentId, email, "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setGrade(MemberGrade.ASSOCIATE);
        m.setGen(41);
        return members.save(m);
    }

    /** 이력이 없는 회원은 행째 사라진다. */
    @Test
    void memberWithoutHistoryIsDeleted() {
        Member m = saved("김없음", "2024011111", "none@hanyang.ac.kr");
        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.DELETED);
        assertThat(members.findById(m.getId())).isEmpty();
    }

    /** 임기 이력이 있으면 행이 남고 개인정보만 지워진다. */
    @Test
    void memberWithTermIsPurgedNotDeleted() {
        Member m = saved("이임원", "2022022222", "exec@hanyang.ac.kr");
        m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
        m = members.save(m);   // save 는 병합된 새 인스턴스를 준다. 버리면 version 이 밀린다

        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.PURGED);

        Member found = members.findById(m.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("이임원");
        assertThat(found.getTerms()).hasSize(1);
        assertThat(found.getEmail()).isNull();
        assertThat(found.getPurgedAt()).isEqualTo(AT);
    }

    /** 스터디 리더는 건너뛴다 — 지금도 삭제가 막혀 있다. */
    @Test
    void studyLeaderIsSkipped() {
        Member m = saved("박리더", "2023033333", "leader@hanyang.ac.kr");
        studies.save(Study.create("자바 스터디", List.of("백엔드"), 6,
                "월 19시", "2026-2학기", "온라인", "함께 읽어요", m.getId()));

        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.SKIPPED_LEADER);
        assertThat(members.findById(m.getId())).isPresent();
        assertThat(members.findById(m.getId()).orElseThrow().getEmail()).isNotNull();
    }

    /** 두 번 파기해도 결과가 같다 — 스윕이 매일 도는 것을 견뎌야 한다. */
    @Test
    void purgingTwiceIsHarmless() {
        Member m = saved("최중복", "2022044444", "dup@hanyang.ac.kr");
        m.setContributor(true);
        m = members.save(m);   // save 는 병합된 새 인스턴스를 준다. 버리면 version 이 밀린다

        assertThat(purger.purge(m, AT)).isEqualTo(MemberPurger.Outcome.PURGED);
        Member again = members.findById(m.getId()).orElseThrow();
        assertThat(purger.purge(again, AT)).isEqualTo(MemberPurger.Outcome.PURGED);
        assertThat(members.findById(m.getId()).orElseThrow().getName()).isEqualTo("최중복");
    }
}
