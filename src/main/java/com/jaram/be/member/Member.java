package com.jaram.be.member;

import jakarta.persistence.*;
import java.time.Instant;
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

    private String title;        // 직책 표시 텍스트
    private String department;    // 부서 (exec 그룹용)

    @Enumerated(EnumType.STRING)
    private MemberCategory category = MemberCategory.contrib;

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
        m.category = MemberCategory.contrib;
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
    public MemberCategory getCategory() { return category; }
    public void setCategory(MemberCategory c) { this.category = c; }
    public String getTitle() { return title; }
    public void setTitle(String t) { this.title = t; }
    public String getDepartment() { return department; }
    public void setDepartment(String d) { this.department = d; }
    public Integer getGen() { return gen; }
    public void setGen(Integer g) { this.gen = g; }
    public String getBio() { return bio; }
    public void setBio(String b) { this.bio = b; }
    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String u) { this.githubUrl = u; }
    public String getBlogUrl() { return blogUrl; }
    public void setBlogUrl(String u) { this.blogUrl = u; }
}
