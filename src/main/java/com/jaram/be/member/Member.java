package com.jaram.be.member;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
    private MemberGrade grade;            // 등급 (승인 시 gen 파생, nullable 이전)

    // 기여자 여부. 임원(임기)·졸업(grade)과 달리 파생할 근거가 없어 그대로 저장한다.
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean contributor = false;

    // 직책 이력. 현직(endGen == null)은 최대 하나이며 title/department 는 여기서 파생한다.
    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.EAGER)
    @OrderBy("startGen ASC")
    private List<MemberTerm> terms = new ArrayList<>();

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
    // 권한은 저장하지 않는다 — 진행 중인 임기가 있으면 임원. 부원(STAFF)도 임원 권한을 갖는다.
    public Authority getAuthority() { return currentTerm().isPresent() ? Authority.OFFICER : Authority.MEMBER; }
    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus s) { this.status = s; }
    public MemberApproval getApproval() { return approval; }
    public void setApproval(MemberApproval a) { this.approval = a; }
    public MemberGrade getGrade() { return grade; }
    public void setGrade(MemberGrade g) { this.grade = g; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }

    // Profile fields (people tab). Read by PeopleService; mutable as a member edits their profile.
    public boolean isContributor() { return contributor; }

    public void setContributor(boolean contributor) { this.contributor = contributor; }

    public List<MemberTerm> getTerms() { return Collections.unmodifiableList(terms); }

    /** 진행 중인 임기. 불변식상 최대 하나다. */
    public Optional<MemberTerm> currentTerm() {
        return terms.stream().filter(MemberTerm::isCurrent).findFirst();
    }

    /** 종료된 임기 중 startGen 이 가장 큰 것. 없으면 empty. */
    public Optional<MemberTerm> lastEndedTerm() {
        return terms.stream().filter(t -> !t.isCurrent())
                .max(Comparator.comparingInt(MemberTerm::getStartGen));
    }

    public MemberTitle getTitle() {
        return currentTerm().map(MemberTerm::getTitle).orElse(null);
    }

    public MemberDepartment getDepartment() {
        return currentTerm().map(MemberTerm::getDepartment).orElse(null);
    }

    /** 같은 (부서, 직책)이면 아무것도 하지 않는다 — 저장할 때마다 길이 0 임기가 쌓이지 않도록. */
    public void assignTerm(MemberDepartment d, MemberTitle t, int currentGen) {
        Optional<MemberTerm> cur = currentTerm();
        if (cur.isPresent() && cur.get().getDepartment() == d && cur.get().getTitle() == t) return;
        cur.ifPresent(term -> term.end(currentGen));
        terms.add(MemberTerm.start(this, d, t, currentGen));
    }

    public void endCurrentTerm(int currentGen) {
        currentTerm().ifPresent(t -> t.end(currentGen));
    }

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
