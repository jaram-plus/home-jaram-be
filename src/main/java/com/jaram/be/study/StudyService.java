package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Eligibility;
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
 * status 는 저장된 생애축이다. cur(승인된 지원 수)·apply(사용자별 상태)만 여기서 파생.
 */
@Service
public class StudyService {

    private static final List<StudyStatus> DEFAULT_LIST =
            List.of(StudyStatus.RECRUITING, StudyStatus.ONGOING);

    private final StudyRepository studies;
    private final StudyApplicationRepository applications;
    private final MemberRepository members;
    private final Eligibility eligibility;

    public StudyService(StudyRepository studies, StudyApplicationRepository applications,
                        MemberRepository members, Eligibility eligibility) {
        this.studies = studies;
        this.applications = applications;
        this.members = members;
        this.eligibility = eligibility;
    }

    // ── UC-T3: 개설 신청 ──
    @Transactional
    public StudyResponse create(StudyCreateRequest req, String leaderId) {
        eligibility.requireActive(leaderId);
        Study saved = studies.save(Study.create(
                req.title(), req.fields(), req.capacity(),
                req.schedule(), null, req.mode(), req.intro(), null, leaderId));
        return toResponse(saved, members.findById(leaderId).orElse(null), leaderId);
    }

    // ── UC-T1: 목록 (기본 RECRUITING + ONGOING, 미인증 시 userId=null) ──
    @Transactional(readOnly = true)
    public List<StudyResponse> list(String userId) {
        List<Study> rows = studies.findByStatusInOrderByCreatedAtDesc(DEFAULT_LIST);
        Map<String, Member> leaders = leadersOf(rows);
        return rows.stream()
                .map(s -> toResponse(s, leaders.get(s.getLeaderId()), userId))
                .toList();
    }

    // ── UC-T2: 지원 ──
    @Transactional
    public void apply(String studyId, String applicantId, String motive) {
        eligibility.requireActive(applicantId);   // 조회보다 먼저다 — 없는 id 에 404 가 앞서면 안 된다
        Study study = loadStudy(studyId);
        if (study.getStatus() != StudyStatus.RECRUITING) {
            throw new ApiException(HttpStatus.CONFLICT, "RECRUIT_CLOSED", "모집 중인 스터디가 아닙니다.");
        }
        if (applicantId.equals(study.getLeaderId())) {
            throw new ApiException(HttpStatus.CONFLICT, "LEADER_SELF", "개설자는 지원할 수 없습니다.");
        }
        applications.findByStudyIdAndApplicantId(studyId, applicantId).ifPresent(a -> {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_APPLIED", "이미 지원한 스터디입니다.");
        });
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
                .map(s -> new MyStudy(s.getId(), s.getTitle(), s.getStatus(), s.getReason()))
                .toList();

        return new MyActivity(apps, myStudies);
    }

    // ── UC-T5: 개설 대기 목록 ──
    @Transactional(readOnly = true)
    public List<PendingStudy> pending() {
        List<Study> rows = studies.findByStatusOrderByCreatedAtDesc(StudyStatus.PENDING);
        Map<String, String> names = memberNames(rows.stream().map(Study::getLeaderId).toList());
        return rows.stream().map(s -> new PendingStudy(
                s.getId(), s.getTitle(), s.getFields(),
                names.getOrDefault(s.getLeaderId(), null),
                cap(s), s.getSchedule(), s.getIntro(),
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
        // 정원을 보지 않는다 — cap 은 상한이 아니라 희망 인원이고, 넘겨 받을지는
        // 스터디장이 판단한다 (D9).
        loadApplication(applicationId).approve();
    }

    @Transactional
    public void rejectApplicant(String applicationId, String reason) {
        loadApplication(applicationId).reject(reason);
    }

    // ── 파생/헬퍼 ──

    private Map<String, Member> leadersOf(List<Study> rows) {
        return members.findAllById(rows.stream().map(Study::getLeaderId).distinct().toList())
                .stream().collect(Collectors.toMap(Member::getId, Function.identity()));
    }

    private StudyResponse toResponse(Study s, Member leader, String userId) {
        return new StudyResponse(
                s.getId(), s.getTitle(), s.getFields(),
                leader == null ? null : leader.getName(),
                leader == null ? null : leader.getGen(),
                s.getIntro(), s.getSchedule(), s.getMode(),
                approvedCount(s.getId()), cap(s),
                s.getStatus(), deriveApply(s, userId));
    }

    // 미인증 → null. leader/승인됨 → JOINED, 대기 → APPLIED, 반려·모집 아님 → CLOSED, 그 외 OPEN.
    private ApplyState deriveApply(Study s, String userId) {
        if (userId == null) return null;
        if (userId.equals(s.getLeaderId())) return ApplyState.JOINED;
        StudyApplication mine = applications.findByStudyIdAndApplicantId(s.getId(), userId).orElse(null);
        if (mine != null) {
            if (mine.getStatus() == ApplicationStatus.APPROVED) return ApplyState.JOINED;
            if (mine.getStatus() == ApplicationStatus.PENDING) return ApplyState.APPLIED;
            // REJECTED: ② 의 '삭제하기'가 이 행을 지우면 다시 OPEN 이 된다 (D11).
            return ApplyState.CLOSED;
        }
        return s.getStatus() == StudyStatus.RECRUITING ? ApplyState.OPEN : ApplyState.CLOSED;
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
