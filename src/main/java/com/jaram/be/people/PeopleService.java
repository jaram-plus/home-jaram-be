package com.jaram.be.people;

import com.jaram.be.member.Member;
import com.jaram.be.member.MemberApproval;
import com.jaram.be.member.MemberCategory;
import com.jaram.be.member.MemberDepartment;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberStatus;
import com.jaram.be.people.dto.PeopleGroup;
import com.jaram.be.people.dto.PeopleResponse;
import com.jaram.be.people.dto.PeopleTab;
import com.jaram.be.people.dto.PersonMember;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UC-P1: list ACTIVE members as the three people tabs. exec is grouped by
 * department; contrib/grad are a single unnamed group. Tab copy (desc/empty)
 * is static and owned here — it lives in FE's people.data.js, mirrored as the
 * source of truth for the contract.
 */
@Service
public class PeopleService {

    private final MemberRepository members;

    public PeopleService(MemberRepository members) { this.members = members; }

    @Transactional(readOnly = true)
    public PeopleResponse list() {
        // 승인된 회원 중 탈퇴자(WITHDRAWN)를 제외한 현 회원(재학/휴학)만 노출.
        List<Member> active = members.findByApproval(MemberApproval.APPROVED).stream()
                .filter(m -> m.getStatus() != MemberStatus.WITHDRAWN)
                .toList();
        return new PeopleResponse(
                execTab(byCategory(active, MemberCategory.exec)),
                flatTab("자람에 힘을 더해주신 분들입니다.", "등록된 기여자가 없습니다.",
                        byCategory(active, MemberCategory.contrib)),
                flatTab("자람을 거쳐 나아간 선배들입니다.", "등록된 졸업자가 없습니다.",
                        byCategory(active, MemberCategory.grad)));
    }

    private List<Member> byCategory(List<Member> all, MemberCategory category) {
        return all.stream().filter(m -> m.hasCategory(category)).toList();
    }

    // exec: one group per department, preserving first-seen order.
    private PeopleTab execTab(List<Member> execs) {
        Map<MemberDepartment, List<PersonMember>> byDept = new LinkedHashMap<>();
        for (Member m : execs) {
            byDept.computeIfAbsent(m.getDepartment(), k -> new ArrayList<>()).add(toCard(m));
        }
        List<PeopleGroup> groups = new ArrayList<>();
        byDept.forEach((dept, cards) ->
                groups.add(new PeopleGroup(dept == null ? null : dept.label(), cards)));
        return new PeopleTab("지금 자람을 이끄는 임원진입니다.", "등록된 임원 정보가 없습니다.", groups);
    }

    // contrib/grad: a single unnamed group, or no groups when empty.
    private PeopleTab flatTab(String desc, String empty, List<Member> people) {
        List<PeopleGroup> groups = people.isEmpty()
                ? List.of()
                : List.of(new PeopleGroup(null, people.stream().map(this::toCard).toList()));
        return new PeopleTab(desc, empty, groups);
    }

    private PersonMember toCard(Member m) {
        return new PersonMember(
                m.getName(),
                roleLabel(m),
                m.getGen() == null ? null : m.getGen() + "기",
                m.getBio(),
                m.getGithubUrl(),
                m.getBlogUrl());
    }

    // PersonMember.role은 required. 직책(title)이 있으면 그 라벨, 없으면 등급(grade)
    // 라벨로 폴백(예 grad 카드 "OB"). 둘 다 없으면 빈 문자열로 non-null 보장.
    private String roleLabel(Member m) {
        if (m.getTitle() != null) return m.getTitle().label();
        if (m.getGrade() != null) return m.getGrade().label();
        return "";
    }
}
