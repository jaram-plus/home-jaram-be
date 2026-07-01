package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.study.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * UC-T1..T8. 개설 신청→개설 승인(임원)→모집→지원(회원)→지원 승인(임원) 2단 승인 흐름.
 * cur(승인된 지원 수)·status(모집 상태)·apply(사용자별 상태)는 저장하지 않고 여기서 파생.
 */
@Service
public class StudyService {

    private final StudyRepository studies;
    private final StudyApplicationRepository applications;
    private final MemberRepository members;

    public StudyService(StudyRepository studies, StudyApplicationRepository applications,
                        MemberRepository members) {
        this.studies = studies;
        this.applications = applications;
        this.members = members;
    }

    // ── UC-T3: 개설 신청 ──
    @Transactional
    public StudyResponse create(StudyCreateRequest req, String leaderId) {
        Study saved = studies.save(Study.create(
                req.title(), req.fields(), req.capacity(),
                req.schedule(), req.period(), req.mode(), req.intro(), leaderId));
        return toResponse(saved, leaderId);
    }

    // ── UC-T1: 목록 (APPROVED만, 미인증 시 userId=null) ──
    @Transactional(readOnly = true)
    public List<StudyResponse> list(String userId) {
        return studies.findByApprovalStatusOrderByCreatedAtDesc(ApprovalStatus.APPROVED).stream()
                .map(s -> toResponse(s, userId))
                .toList();
    }

    // ── UC-T2: 지원 ──
    @Transactional
    public void apply(String studyId, String applicantId, String motive) {
        Study study = loadStudy(studyId);
        if (study.getApprovalStatus() != ApprovalStatus.APPROVED) {
            throw new ApiException(HttpStatus.CONFLICT, "RECRUIT_CLOSED", "모집 중인 스터디가 아닙니다.");
        }
        if (applicantId.equals(study.getLeaderId())) {
            throw new ApiException(HttpStatus.CONFLICT, "LEADER_SELF", "개설자는 지원할 수 없습니다.");
        }
        applications.findByStudyIdAndApplicantId(studyId, applicantId).ifPresent(a -> {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_APPLIED", "이미 지원한 스터디입니다.");
        });
        if (approvedCount(studyId) >= cap(study)) {
            throw new ApiException(HttpStatus.CONFLICT, "RECRUIT_CLOSED", "모집이 마감되었습니다.");
        }
        applications.save(StudyApplication.create(studyId, applicantId, motive));
    }

    // ── UC-T4: 내 활동 ──
    @Transactional(readOnly = true)
    public MyActivity myActivity(String userId) {
        List<StudyApplication> myApps = applications.findByApplicantIdOrderByCreatedAtDesc(userId);
        Map<String, Study> studyById = studies.findAllById(
                        myApps.stream().map(StudyApplication::getStudyId).toList()).stream()
                .collect(Collectors.toMap(Study::getId, Function.identity()));

        List<MyApp> apps = myApps.stream().map(a -> {
            Study s = studyById.get(a.getStudyId());
            return new MyApp(a.getId(), a.getStudyId(),
                    s == null ? null : s.getTitle(), a.getStatus(), a.getReason());
        }).toList();

        List<MyStudy> myStudies = studies.findByLeaderIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> new MyStudy(s.getId(), s.getTitle(), s.getApprovalStatus(),
                        deriveStatus(s), s.getReason()))
                .toList();

        return new MyActivity(apps, myStudies);
    }

    // ── UC-T5: 개설 대기 목록 ──
    @Transactional(readOnly = true)
    public List<PendingStudy> pending() {
        List<Study> rows = studies.findByApprovalStatusOrderByCreatedAtDesc(ApprovalStatus.PENDING);
        Map<String, String> names = memberNames(rows.stream().map(Study::getLeaderId).toList());
        return rows.stream().map(s -> new PendingStudy(
                s.getId(), s.getTitle(), s.getFields(),
                names.getOrDefault(s.getLeaderId(), null),
                cap(s), s.getSchedule(), s.getPeriod(), s.getIntro(),
                s.getCreatedAt().toString())).toList();
    }

    // ── UC-T6: 개설 승인/거절 ──
    @Transactional
    public void approveStudy(String studyId) { loadStudy(studyId).approve(); }

    @Transactional
    public void rejectStudy(String studyId, String reason) { loadStudy(studyId).reject(reason); }

    // ── UC-T7: 신청자 목록 (승인 대기) ──
    @Transactional(readOnly = true)
    public List<Applicant> applicants() {
        List<StudyApplication> rows = applications.findByStatusOrderByCreatedAtDesc(ApplicationStatus.PENDING);
        Map<String, Study> studyById = studies.findAllById(
                        rows.stream().map(StudyApplication::getStudyId).toList()).stream()
                .collect(Collectors.toMap(Study::getId, Function.identity()));
        Map<String, Member> memberById = members.findAllById(
                        rows.stream().map(StudyApplication::getApplicantId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));

        return rows.stream().map(a -> {
            Study s = studyById.get(a.getStudyId());
            Member m = memberById.get(a.getApplicantId());
            return new Applicant(
                    a.getId(), a.getStudyId(),
                    s == null ? null : s.getTitle(),
                    m == null ? null : m.getName(),
                    m == null ? null : m.getStudentId(),
                    a.getMotive(), a.getCreatedAt().toString());
        }).toList();
    }

    // ── UC-T8: 신청자 승인/거절 ──
    @Transactional
    public void approveApplicant(String applicationId) {
        StudyApplication a = loadApplication(applicationId);
        Study study = loadStudy(a.getStudyId());
        if (approvedCount(a.getStudyId()) >= cap(study)) {
            throw new ApiException(HttpStatus.CONFLICT, "CAPACITY_FULL", "정원이 초과되었습니다.");
        }
        a.approve();
    }

    @Transactional
    public void rejectApplicant(String applicationId, String reason) {
        loadApplication(applicationId).reject(reason);
    }

    // ── 파생/헬퍼 ──

    private StudyResponse toResponse(Study s, String userId) {
        int cur = approvedCount(s.getId());
        int cap = cap(s);
        return new StudyResponse(
                s.getId(), s.getTitle(), s.getFields(),
                memberNames(List.of(s.getLeaderId())).getOrDefault(s.getLeaderId(), null),
                s.getSchedule(), s.getPeriod(), s.getMode(),
                cur, cap, deriveStatus(cur, cap), deriveApply(s, cur, cap, userId));
    }

    private StudyStatus deriveStatus(Study s) {
        return deriveStatus(approvedCount(s.getId()), cap(s));
    }

    private StudyStatus deriveStatus(int cur, int cap) {
        return cur >= cap ? StudyStatus.CLOSED : StudyStatus.RECRUITING;
    }

    // 미인증 → null. leader/승인됨 → JOINED, 대기 → APPLIED, 마감 → CLOSED, 그 외 OPEN.
    private ApplyState deriveApply(Study s, int cur, int cap, String userId) {
        if (userId == null) return null;
        if (userId.equals(s.getLeaderId())) return ApplyState.JOINED;
        StudyApplication mine = applications.findByStudyIdAndApplicantId(s.getId(), userId).orElse(null);
        if (mine != null) {
            if (mine.getStatus() == ApplicationStatus.APPROVED) return ApplyState.JOINED;
            if (mine.getStatus() == ApplicationStatus.PENDING) return ApplyState.APPLIED;
            // REJECTED: the unique (study,applicant) row blocks re-apply, so never OPEN.
            return ApplyState.CLOSED;
        }
        return cur >= cap ? ApplyState.CLOSED : ApplyState.OPEN;
    }

    private int approvedCount(String studyId) {
        return applications.countByStudyIdAndStatus(studyId, ApplicationStatus.APPROVED);
    }

    private int cap(Study s) { return s.getCapacity() == null ? 0 : s.getCapacity(); }

    private Map<String, String> memberNames(List<String> ids) {
        return members.findAllById(ids).stream()
                .collect(Collectors.toMap(Member::getId, Member::getName));
    }

    private Study loadStudy(String id) {
        return studies.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "스터디를 찾을 수 없습니다."));
    }

    private StudyApplication loadApplication(String id) {
        return applications.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "스터디 신청을 찾을 수 없습니다."));
    }
}
