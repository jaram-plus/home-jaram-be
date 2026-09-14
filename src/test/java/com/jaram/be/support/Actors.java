package com.jaram.be.support;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.member.MemberTitle;
import com.jaram.be.security.JwtProvider;
import com.jaram.be.security.authz.Role;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트에서 "이 Role 인 사람으로 요청한다"를 한 줄로 만든다.
 *
 * 예전에는 DB 에 없는 id 로 토큰만 찍어 썼다. 권한이 토큰 클레임에 실려 있어서
 * 그래도 통했지만, 이제 권한은 요청 시점에 회원의 현직 임기에서 나온다 — 행이 없으면
 * 권한도 없다. 그래서 행위자는 실제로 저장된다.
 *
 * 학번과 이메일은 호출할 때마다 달라진다. member 테이블의 UNIQUE 제약 때문이며,
 * 한 테스트가 행위자를 둘 이상 만들 때 충돌하지 않게 한다. 학번이 99 로 시작하는
 * 것도 같은 이유다 — 기존 픽스처가 쓰는 2023xxxxxx 대역과 겹치면 안 된다.
 *
 * component scan 에 잡히는 이유: @SpringBootApplication 이 com.jaram.be 를 스캔하고
 * 테스트 클래스도 같은 패키지의 클래스패스에 있다. 이 동작이 부담스러워지면
 * 생성자를 public 으로 둔 채 각 테스트가 new Actors(members, jwt) 로 만들면 된다.
 */
@Component
public class Actors {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final MemberRepository members;
    private final JwtProvider jwt;

    public Actors(MemberRepository members, JwtProvider jwt) {
        this.members = members;
        this.jwt = jwt;
    }

    /** 이 Role 인 승인된 활동 회원을 저장한다. MEMBER 는 임기 없이 저장된다. */
    public Member save(Role role) {
        int n = SEQ.incrementAndGet();
        Member m = Member.newPending(
                "행위자" + n, "99%08d".formatted(n), "actor%d@hanyang.ac.kr".formatted(n), "hash");
        m.setApproval(MemberApproval.APPROVED);
        m.setStatus(MemberStatus.ACTIVE);
        m.setGrade(MemberGrade.REGULAR);
        m.setGen(41);
        assignTerm(m, role);
        return members.save(m);
    }

    public String token(Role role) { return tokenFor(save(role)); }

    public String tokenFor(Member m) {
        return jwt.generate(m.getId(), m.getName(), m.getEmail());
    }

    /**
     * 지금까지 Authority.OFFICER 토큰이 뜻하던 것 — 관리자 화면 전부를 쓸 수 있는 사람.
     * 권한이 좁은 Role 로 막히는지 보려는 테스트는 token(Role.X) 를 직접 쓴다.
     */
    public String officer() { return token(Role.PRESIDENT); }

    /** 임기 없는 일반 회원. */
    public String member() { return token(Role.MEMBER); }

    private void assignTerm(Member m, Role role) {
        switch (role) {
            case PRESIDENT -> m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.PRESIDENT, 41);
            case VICE_PRESIDENT -> m.assignTerm(MemberDepartment.LEADERSHIP, MemberTitle.VICE_PRESIDENT, 41);
            case SERVER_ADMIN -> m.assignTerm(MemberDepartment.INFRA, MemberTitle.SERVER_ADMIN, 41);
            case ACADEMIC_LEAD -> m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.LEAD, 41);
            case ACADEMIC_STAFF -> m.assignTerm(MemberDepartment.ACADEMIC, MemberTitle.STAFF, 41);
            case PR_LEAD -> m.assignTerm(MemberDepartment.PR, MemberTitle.LEAD, 41);
            case PR_STAFF -> m.assignTerm(MemberDepartment.PR, MemberTitle.STAFF, 41);
            case FINANCE_LEAD -> m.assignTerm(MemberDepartment.FINANCE, MemberTitle.LEAD, 41);
            case FINANCE_STAFF -> m.assignTerm(MemberDepartment.FINANCE, MemberTitle.STAFF, 41);
            case MEMBER -> { }   // 임기 없음이 곧 MEMBER 다
        }
    }
}
