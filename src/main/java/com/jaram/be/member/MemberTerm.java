package com.jaram.be.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 한 회원이 한 직책을 맡은 기간. 1년 임기는 startGen == endGen, 연임은 범위,
 * 현직은 endGen == null 로 표현한다. Member 를 통해서만 생성·종료된다.
 */
@Entity
@Table(name = "member_term")
public class MemberTerm {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberDepartment department;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MemberTitle title;

    @Column(nullable = false)
    private int startGen;

    private Integer endGen;   // null = 현직

    protected MemberTerm() { }

    static MemberTerm start(Member m, MemberDepartment d, MemberTitle t, int startGen) {
        MemberTerm term = new MemberTerm();
        term.id = UUID.randomUUID().toString();
        term.member = m;
        term.department = d;
        term.title = t;
        term.startGen = startGen;
        return term;
    }

    public MemberDepartment getDepartment() { return department; }

    public MemberTitle getTitle() { return title; }

    public int getStartGen() { return startGen; }

    public Integer getEndGen() { return endGen; }

    public boolean isCurrent() { return endGen == null; }

    public String label() { return title.label(department); }

    void end(int gen) { this.endGen = gen; }
}
