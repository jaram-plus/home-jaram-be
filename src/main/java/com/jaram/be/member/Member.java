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
    private Authority authority = Authority.MEMBER;

    @Enumerated(EnumType.STRING)
    private MemberTitle title;            // 직책 (nullable)
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

    @Enumerated(EnumType.STRING)
    private MemberStatus status = MemberStatus.PENDING;

    private Instant createdAt = Instant.now();

    protected Member() { }

    public static Member newPending(String name, String studentId, String email, String passwordHash) {
        Member m = new Member();
        m.id = UUID.randomUUID().toString();
        m.name = name;
        m.studentId = studentId;
        m.email = email;
        m.passwordHash = passwordHash;
        m.authority = Authority.MEMBER;
        m.categories = new LinkedHashSet<>(Set.of(MemberCategory.regular));
        m.status = MemberStatus.PENDING;
        m.createdAt = Instant.now();
        return m;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getStudentId() { return studentId; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String h) { this.passwordHash = h; }
    public Authority getAuthority() { return authority; }
    public MemberStatus getStatus() { return status; }
    public void setStatus(MemberStatus s) { this.status = s; }
    public Instant getCreatedAt() { return createdAt; }

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
}
