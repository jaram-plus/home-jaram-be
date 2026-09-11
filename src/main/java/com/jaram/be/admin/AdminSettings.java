package com.jaram.be.admin;

import jakarta.persistence.*;

/**
 * 학회 단일 설정 로우 (id 고정 SINGLETON). driveConnected/driveFolder는 Drive 연동(P7)
 * 상태를 반영하며 여기서는 저장만 한다. autoPromote는 플래그만 저장(승격 잡은 미구현).
 */
@Entity
@Table(name = "admin_settings")
public class AdminSettings {

    static final String SINGLETON_ID = "SINGLETON";

    @Id
    private String id = SINGLETON_ID;

    private String semester;         // 예 "2026-2학기"
    private Integer currentCohort;   // 예 41
    private boolean autoPromote;
    private boolean driveConnected;
    private String driveFolder;      // nullable

    // 푸터가 쓰는 학회 외부 채널 주소. 등록하지 않은 채널은 null (빈 문자열이 아니다).
    private String linkGithub;
    private String linkInstagram;
    private String linkBlog;
    private String linkDiscord;

    protected AdminSettings() { }

    static AdminSettings defaults() {
        AdminSettings s = new AdminSettings();
        s.id = SINGLETON_ID;
        s.semester = "";
        s.currentCohort = 0;
        s.autoPromote = false;
        s.driveConnected = false;
        s.driveFolder = null;
        s.linkGithub = null;
        s.linkInstagram = null;
        s.linkBlog = null;
        s.linkDiscord = null;
        return s;
    }

    public String getSemester() { return semester; }
    public void setSemester(String v) { this.semester = v; }
    public Integer getCurrentCohort() { return currentCohort; }
    public void setCurrentCohort(Integer v) { this.currentCohort = v; }
    public boolean isAutoPromote() { return autoPromote; }
    public void setAutoPromote(boolean v) { this.autoPromote = v; }
    public boolean isDriveConnected() { return driveConnected; }
    public void setDriveConnected(boolean v) { this.driveConnected = v; }
    public String getDriveFolder() { return driveFolder; }
    public void setDriveFolder(String v) { this.driveFolder = v; }
    public String getLinkGithub() { return linkGithub; }
    public void setLinkGithub(String v) { this.linkGithub = v; }
    public String getLinkInstagram() { return linkInstagram; }
    public void setLinkInstagram(String v) { this.linkInstagram = v; }
    public String getLinkBlog() { return linkBlog; }
    public void setLinkBlog(String v) { this.linkBlog = v; }
    public String getLinkDiscord() { return linkDiscord; }
    public void setLinkDiscord(String v) { this.linkDiscord = v; }
}
