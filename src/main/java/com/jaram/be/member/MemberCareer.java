package com.jaram.be.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 졸업생의 자람 밖 이력 한 줄. 자람 안에서의 이력은 MemberTerm(임기)이 따로 들고 있고,
 * 이쪽은 졸업 후 어디서 무슨 일을 했는가만 담는다.
 *
 * at 은 'YYYY.MM' 같은 표시용 자유 문자열이다 — 입사 연월만 아는 경우가 대부분이라
 * 날짜 타입으로 좁히지 않았고, 사전순 내림차순이 곧 최신순이 되도록 화면과 약속했다.
 * Member 를 통해서만 만들어지고 통째로 교체된다.
 */
@Entity
@Table(name = "member_career")
public class MemberCareer {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "at_text")
    private String at;

    @Column(name = "org")
    private String org;

    @Column(name = "job")
    private String job;

    protected MemberCareer() { }

    static MemberCareer of(Member m, String at, String org, String job) {
        MemberCareer c = new MemberCareer();
        c.id = UUID.randomUUID().toString();
        c.member = m;
        c.at = at;
        c.org = org;
        c.job = job;
        return c;
    }

    public String getAt() { return at; }

    public String getOrg() { return org; }

    public String getJob() { return job; }
}
