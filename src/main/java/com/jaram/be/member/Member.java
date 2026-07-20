package com.jaram.be.member;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "member",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "email"),
           @UniqueConstraint(columnNames = "studentId")
       })
public class Member {

    @Id
    private String id;

    private String name;
    private String studentId;
    private String email;
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private MemberTitle title;            // 직책 (nullable). 권한(authority)의 단일 진실원.
    @Enumerated(EnumType.STRING)
    private MemberGrade grade;            // 등급 (승인 시 gen 파생, nullable 이전)
    @Enumerated(EnumType.STRING)
    private MemberDepartment department;  // 부서 (exec 그룹용, nullable)

    // A member is 일반(regular) by default and may be awarded any of
    // exec/contrib/grad simultaneously. regular and the awards are mutually
    // exclusive: awarding drops regular, revoking the last award restores it.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "member_category",
                     joinColumns = @JoinColumn(name = "member_id"))
    @Column(name = "category")
    @Enumerated(EnumType.STRING)
    private Set<MemberCategory> categories = new LinkedHashSet<>(Set.of(MemberCategory.regular));

    private Integer gen;          // 기수 정수 (응답은 "{gen}기")
    @Column(length = 1000)
    private String bio;
    private String githubUrl;
    private String blogUrl;

    private String faculty;       // 학부 (자유 입력 텍스트, 가입 시 입력)
    private String phone;         // 휴대전화 (하이픈 포함 형식 저장)

    // 승인축: 가입 승인 상태. 활동축(status)과 분리.
    @Enumerated(EnumType.STRING)
    private MemberApproval approval = MemberApproval.PENDING;

    // 활동축의 단일 진실원. 가입 시 SignupRequest.enrolled로 파생, 이후 admin이 변경.
    @Enumerated(EnumType.STRING)
    private MemberStatus status = MemberStatus.ACTIVE;

    private Instant createdAt = Instant.now();

    @Version
    private Long version;   // 관리자 일괄 편집 낙관적 잠금

    protected Member() { }

    public static Member newPending(String name, String studentId, String email, String passwordHash) {
        Member m = new Member();
        m.id = UUID.randomUUID().toString();
        m.name = name;
        m.studentId = studentId;
        m.email = email;
        m.passwordHash = passwordHash;
        m.categories = new LinkedHashSet<>(Set.of(MemberCategory.regular));
        m.approval = MemberApproval.PENDING;
        m.status = MemberStatus.ACTIVE;
        m.createdAt = Instant.now();
        return m;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getStudentId() { return studentId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    // 권한은 저장하지 않는다 — 직책이 있으면 임원. 부원(STAFF)도 임원 권한을 갖는다.
    public Authority getAuthority() { return title != null ? Authority.OFFICER : Authority.MEMBER; }
    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus s) { this.status = s; }
    public MemberApproval getApproval() { return approval; }
    public void setApproval(MemberApproval a) { this.approval = a; }
    public MemberGrade getGrade() { return grade; }
    public void setGrade(MemberGrade g) { this.grade = g; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }

    // Profile fields (people tab). Read by PeopleService; mutable as a member edits their profile.
    public Set<MemberCategory> getCategories() { return Collections.unmodifiableSet(categories); }
    public boolean hasCategory(MemberCategory c) { return categories.contains(c); }

    // award(regular) is a no-op; awarding any real category drops regular.
    public void award(MemberCategory c) {
        if (c == MemberCategory.regular) return;
        categories.remove(MemberCategory.regular);
        categories.add(c);
    }

    // revoking the last award restores regular so a member is never categoryless.
    public void revoke(MemberCategory c) {
        categories.remove(c);
        if (categories.isEmpty()) categories.add(MemberCategory.regular);
    }
    public MemberTitle getTitle() { return title; }
    public void setTitle(MemberTitle t) { this.title = t; }
    public MemberDepartment getDepartment() { return department; }
    public void setDepartment(MemberDepartment d) { this.department = d; }
    public Integer getGen() { return gen; }
    public void setGen(Integer g) { this.gen = g; }
    public String getBio() { return bio; }
    public void setBio(String b) { this.bio = b; }
    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String u) { this.githubUrl = u; }
    public String getBlogUrl() { return blogUrl; }
    public void setBlogUrl(String u) { this.blogUrl = u; }
    public String getFaculty() { return faculty; }
    public void setFaculty(String f) { this.faculty = f; }
    public String getPhone() { return phone; }
    public void setPhone(String p) { this.phone = p; }
}
