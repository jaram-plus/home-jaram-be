package com.jaram.be.admin;

import com.jaram.be.member.Gen;
import jakarta.persistence.*;

import java.time.LocalDate;

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

    private Integer currentCohort;   // 예 41. 0/null 이면 '자동'
    private Integer semesterTerm;    // 1|2. null 이면 '자동'

    // override 를 언제 눌렀는지. 그래야 기수는 해가 바뀔 때마다 한 칸 올리고,
    // 학기는 다음 학기가 오면 자동값으로 돌아갈 수 있다.
    private LocalDate cohortSetOn;
    private LocalDate semesterTermSetOn;
    // 마지막으로 학기 전환 스윕을 실행한 학기. 둘 다 null 이면 아직 한 번도 돌지 않았다.
    private Integer lastRolloverYear;
    private Integer lastRolloverTerm;

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
        s.currentCohort = 0;
        s.semesterTerm = null;
        s.cohortSetOn = null;
        s.semesterTermSetOn = null;
        s.lastRolloverYear = null;
        s.lastRolloverTerm = null;
        s.autoPromote = false;
        s.driveConnected = false;
        s.driveFolder = null;
        s.linkGithub = null;
        s.linkInstagram = null;
        s.linkBlog = null;
        s.linkDiscord = null;
        return s;
    }

    /** 3~8월은 1학기, 9~2월은 2학기. 1~2월은 아직 3월 전이라 직전 2학기가 이어진다. */
    static int autoTerm(LocalDate on) {
        int month = on.getMonthValue();
        return (month >= 3 && month <= 8) ? 1 : 2;
    }

    /** 운영이 눌러 둔 학기를 쓰되, 그 학기를 벗어나면 자동값으로 돌아간다. */
    int effectiveTerm(LocalDate today) {
        if (semesterTerm == null || semesterTermSetOn == null) return autoTerm(today);
        boolean sameTerm = semesterTermSetOn.getYear() == today.getYear()
                && autoTerm(semesterTermSetOn) == autoTerm(today);
        return sameTerm ? semesterTerm : autoTerm(today);
    }

    /** 마지막으로 전환을 실행한 학기. null 이면 아직 한 번도 돌지 않았다. */
    Semester lastRollover() {
        if (lastRolloverYear == null || lastRolloverTerm == null) return null;
        return new Semester(lastRolloverYear, lastRolloverTerm);
    }

    void setLastRollover(Semester s) {
        this.lastRolloverYear = s.year();
        this.lastRolloverTerm = s.term();
    }

    /** 설정해 둔 기수는 해가 바뀔 때마다 한 칸 오른다. 미설정이면 창립 연도 기준 계산값. */
    int effectiveGen(LocalDate today) {
        if (currentCohort == null || currentCohort <= 0 || cohortSetOn == null) {
            return Gen.at(today.getYear());
        }
        return currentCohort + (today.getYear() - cohortSetOn.getYear());
    }

    void overrideTerm(int term, LocalDate on) {
        this.semesterTerm = term;
        this.semesterTermSetOn = on;
    }

    /** 0 이하·null 은 '자동으로 되돌린다'는 뜻이다. */
    void overrideGen(Integer gen, LocalDate on) {
        boolean auto = gen == null || gen <= 0;
        this.currentCohort = auto ? 0 : gen;
        this.cohortSetOn = auto ? null : on;
    }

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
