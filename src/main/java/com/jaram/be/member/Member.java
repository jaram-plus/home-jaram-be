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

    // 졸업 후 이력. 임기(terms)와 달리 관리자가 통째로 고쳐 넣는다 — 자람 밖 일이라
    // 서버가 지켜야 할 불변식이 없고, 표에 보이는 순서 그대로 저장한다.
    @OneToMany(mappedBy = "member", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.EAGER)
    private List<MemberCareer> careers = new ArrayList<>();

    private Integer gradYear;     // 졸업연도. 졸업생만 채워지며 그전에는 null.

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

    // 생애주기. 전부 nullable 이며 스윕(MemberLifecycleService)과 본인 요청이 채운다.
    private Instant reregisterRequestedAt;   // null = 재등록 미신청
    private Instant withdrawnAt;             // 6개월 뒤 파기의 기준
    private Instant purgedAt;                // 개인정보 파기 시각. 이력만 남은 회원

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

    /**
     * 학기 전환 대상. 휴학·OB·현직 임원은 면제한다.
     *
     * 승인축도 함께 본다. 가입 대기 회원은 status 기본값이 ACTIVE 라, 승인을 보지 않으면
     * 아직 승인되지 않은 신청자까지 재등록 대상이 된다 — 그러면 임원이 가입을 승인해도
     * (approve 는 승인축만 바꾼다) 활동축이 REREGISTER 로 남아, 방금 들어온 신입이
     * 첫 로그인에서 재등록 팝업을 보고 신청류가 막힌다.
     */
    public boolean isRolloverTarget() {
        return approval == MemberApproval.APPROVED
                && status == MemberStatus.ACTIVE
                && grade != MemberGrade.OB
                && currentTerm().isEmpty();
    }

    public void markReregistrationRequired() { this.status = MemberStatus.REREGISTER; }

    public Instant getReregisterRequestedAt() { return reregisterRequestedAt; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public Instant getPurgedAt() { return purgedAt; }

    /** 재등록 신청. 이미 신청했으면 시각을 덮지 않는다 — 다시 눌러도 처음 신청이 남는다. */
    public void requestReregistration(Instant at) {
        if (reregisterRequestedAt == null) reregisterRequestedAt = at;
    }

    public void completeReregistration() {
        this.status = MemberStatus.ACTIVE;
        this.reregisterRequestedAt = null;
    }

    public void withdraw(Instant at) {
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = at;
    }

    /** 남길 이력이 있는가. 있으면 행을 지우지 않고 개인정보만 파기한다. */
    public boolean hasHistory() { return contributor || !terms.isEmpty(); }

    /**
     * 개인정보 파기. 이름·기수·임기 이력은 남는다 — 임기 기록과 출석·신청 기록의
     * 참조가 끊기지 않게 하는 것이 목적이다.
     *
     * email 이 비면 findByEmail 로 찾히지 않아 로그인이 막힌다. studentId·email 은
     * UNIQUE 지만 PostgreSQL 은 NULL 을 중복으로 보지 않아 여러 행이 비어 있어도 된다.
     */
    public void purge(Instant at) {
        this.studentId = null;
        this.email = null;
        this.passwordHash = null;
        this.phone = null;
        this.faculty = null;
        this.bio = null;
        this.githubUrl = null;
        this.blogUrl = null;
        this.purgedAt = at;
    }

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
        // 임기를 받은 사람은 기여자다. 임기가 끝나도 이력이므로 되돌리지 않는다.
        this.contributor = true;
        Optional<MemberTerm> cur = currentTerm();
        if (cur.isPresent() && cur.get().getDepartment() == d && cur.get().getTitle() == t) return;
        cur.ifPresent(term -> term.end(currentGen));
        terms.add(MemberTerm.start(this, d, t, currentGen));
    }

    public void endCurrentTerm(int currentGen) {
        currentTerm().ifPresent(t -> t.end(currentGen));
    }

    public List<MemberCareer> getCareers() { return Collections.unmodifiableList(careers); }

    /**
     * 이력을 통째로 갈아 끼운다. 줄마다 고치지 않고 교체하는 이유는 화면이 표 전체를
     * 한 번에 저장하기 때문이다 — orphanRemoval 이 빠진 줄을 지운다.
     */
    public void replaceCareers(List<CareerEntry> entries) {
        careers.clear();
        entries.forEach(e -> careers.add(MemberCareer.of(this, e.at(), e.org(), e.job())));
    }

    /** 가장 최근 이력. at 은 'YYYY.MM' 꼴이라 사전순 내림차순이 곧 최신순이다. */
    public Optional<MemberCareer> latestCareer() {
        return careers.stream()
                .filter(c -> c.getAt() != null)
                .max(Comparator.comparing(MemberCareer::getAt));
    }

    /** replaceCareers 의 입력 한 줄. 저장 전이라 엔티티가 아니다. */
    public record CareerEntry(String at, String org, String job) { }

    public Integer getGradYear() { return gradYear; }
    public void setGradYear(Integer y) { this.gradYear = y; }

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
