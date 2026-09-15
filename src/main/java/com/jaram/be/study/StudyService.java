package com.jaram.be.study;

import com.jaram.be.common.ApiException;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.security.authz.Eligibility;
import com.jaram.be.study.dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 목록에서 고를 수 있는 상태. PENDING·REJECTED 는 남의 개설 신청이라 새면 안 된다. */
    private static final Set<StudyStatus> BROWSABLE =
            EnumSet.of(StudyStatus.RECRUITING, StudyStatus.ONGOING, StudyStatus.FINISHED);

    private final StudyRepository studies;
    private final StudyApplicationRepository applications;
    private final StudyWeekRepository weeks;
    private final StudyRecruitmentRepository recruitment;
    private final MemberRepository members;
    private final Eligibility eligibility;

    public StudyService(StudyRepository studies, StudyApplicationRepository applications,
                        StudyWeekRepository weeks, StudyRecruitmentRepository recruitment,
                        MemberRepository members, Eligibility eligibility) {
        this.studies = studies;
        this.applications = applications;
        this.weeks = weeks;
        this.recruitment = recruitment;
        this.members = members;
        this.eligibility = eligibility;
    }

    // ── UC-T3: 개설 신청 ──
    @Transactional
    public StudyResponse create(StudyCreateRequest req, String leaderId) {
        eligibility.requireActive(leaderId);
        // 화면이 버튼을 숨기는 것은 통제가 아니다 — 서버가 거절해야 한다.
        if (!recruitmentOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "RECRUIT_CLOSED",
                    "지금은 스터디 개설 신청을 받지 않습니다.");
        }
        requireContiguousWeeks(req.weeks());
        Study saved = studies.save(Study.create(
                req.title(), req.fields(), req.capacity(),
                req.schedule(), req.place(), req.mode(), req.intro(), req.contact(), leaderId));
        req.weeks().forEach(w -> weeks.save(
                StudyWeek.create(saved.getId(), w.weekNo(), w.title(), w.content())));
        return toResponse(saved, members.findById(leaderId).orElse(null), leaderId);
    }

    /**
     * 1부터 빈칸 없이. 구멍이 있으면 ② 의 "가장 빠른 빈 주차" 가 흔들리고, 중복이 있으면
     * unique 제약이 500 으로 터진다 — 둘 다 여기서 422 로 막는다.
     */
    private void requireContiguousWeeks(List<StudyCreateRequest.WeekInput> input) {
        List<Integer> nos = input.stream().map(StudyCreateRequest.WeekInput::weekNo).sorted().toList();
        for (int i = 0; i < nos.size(); i++) {
            if (nos.get(i) != i + 1) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
                        "커리큘럼 주차는 1부터 빈칸 없이 이어져야 합니다.",
                        Map.of("weeks", "주차 번호가 1..%d 가 아닙니다.".formatted(nos.size())));
            }
        }
    }

    // ── 모집 토글 ──

    /** 행이 없으면 '닫혀 있다'. 기본을 열어 두면 아무도 안 눌렀을 때 개설이 열린다. */
    @Transactional(readOnly = true)
    public boolean recruitmentOpen() {
        return recruitment.findById(StudyRecruitment.SINGLETON_ID)
                .map(StudyRecruitment::isOpen)
                .orElse(false);
    }

    @Transactional
    public void setRecruitmentOpen(boolean open) {
        StudyRecruitment r = recruitment.findById(StudyRecruitment.SINGLETON_ID)
                .orElseGet(StudyRecruitment::closed);
        r.setOpen(open);
        recruitment.save(r);
    }

    // ── UC-T1: 목록 (기본 RECRUITING + ONGOING, 미인증 시 userId=null) ──
    @Transactional(readOnly = true)
    public StudyList list(String userId, String statusParam) {
        List<StudyStatus> want = statusParam == null || statusParam.isBlank()
                ? DEFAULT_LIST
                : List.of(browsable(statusParam));
        List<Study> rows = studies.findByStatusInOrderByCreatedAtDesc(want);
        Map<String, Member> leaders = leadersOf(rows);
        List<StudyResponse> items = rows.stream()
                .map(s -> toResponse(s, leaders.get(s.getLeaderId()), userId))
                .toList();
        return new StudyList(recruitmentOpen(), items);
    }

    /**
     * 파라미터를 열거형으로 바인딩하지 않고 직접 파싱한다. 바인딩에 맡기면 오타 하나가
     * MethodArgumentTypeMismatchException 이 되고, GlobalExceptionHandler 의 포괄
     * 핸들러가 그것을 500 으로 만든다.
     */
    private StudyStatus browsable(String raw) {
        StudyStatus s;
        try {
            s = StudyStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            s = null;
        }
        if (s == null || !BROWSABLE.contains(s)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
                    "조회할 수 없는 상태입니다.",
                    Map.of("status", "RECRUITING, ONGOING, FINISHED 중 하나여야 합니다."));
        }
        return s;
    }

    // ── UC-T9: 상세 ──

    @Transactional(readOnly = true)
    public StudyDetail detail(String id, String userId) {
        Study s = loadStudy(id);
        Member leader = members.findById(s.getLeaderId()).orElse(null);
        List<WeekEntry> curriculum = weeks.findByStudyIdOrderByWeekNoAsc(id).stream()
                .map(w -> new WeekEntry(w.getWeekNo(), w.getTitle(), w.getContent()))
                .toList();
        return new StudyDetail(
                s.getId(), s.getTitle(), s.getFields(), s.getIntro(),
                leader == null ? null : leader.getName(),
                leader == null ? null : leader.getGen(),
                s.getSchedule(), s.getPlace(), s.getMode(), s.getContact(),
                approvedCount(id), cap(s), s.getStatus(), deriveApply(s, userId),
                curriculum, roster(s));
    }

    /**
     * 대기 + 승인. 반려와 스터디장은 뺀다 — 스터디장은 지원자가 아니고 이미 제목 아래에 있다.
     *
     * 정렬이 기수 → 이름인 것은 규칙이다. 신청 순으로 세우면 "먼저 신청했는데 아직 뒤에
     * 있다"가 순서에서 읽혀, 숨긴 승인 상태가 새어 나온다.
     */
    private List<RosterEntry> roster(Study s) {
        List<StudyApplication> rows = applications.findByStudyIdAndStatusIn(
                s.getId(), List.of(ApplicationStatus.PENDING, ApplicationStatus.APPROVED));
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(StudyApplication::getApplicantId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        return rows.stream()
                .map(a -> byId.get(a.getApplicantId()))
                .filter(Objects::nonNull)
                .filter(m -> !m.getId().equals(s.getLeaderId()))
                .sorted(Comparator
                        .comparing(Member::getGen, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Member::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(m -> new RosterEntry(maskStudentId(m.getStudentId()), m.getGen(), m.getName()))
                .toList();
    }

    /**
     * 앞 4자리 + 가운데 전부 '*' + 뒤 1자리. 길이를 보존한다.
     *
     * 마스킹을 서버가 하는 이유: 전체 학번을 내려보내고 화면에서 가리면 개발자 도구로
     * 그대로 보인다.
     */
    static String maskStudentId(String id) {
        if (id == null || id.length() < 6) return id;   // 방어. 학번은 ^\d{8,10}$
        return id.substring(0, 4)
                + "*".repeat(id.length() - 5)
                + id.substring(id.length() - 1);
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
                .map(s -> new MyStudy(s.getId(), s.getTitle(), s.getStatus(), s.getReason(),
                        pendingCount(s)))
                .toList();

        return new MyActivity(apps, myStudies);
    }

    /**
     * 모집 중인 스터디의 대기 신청 수. 그 외에는 null 이다.
     *
     * '내 스터디' 카드가 스터디장에게 "지금 할 일이 있는가"를 말하는 유일한 값이다(② §9).
     * RECRUITING 이 아닐 때 0 을 주면 화면이 "대기 0건"으로 읽어 버린다 — 셀 단계가
     * 아니라는 뜻이므로 null 이어야 한다.
     */
    private Integer pendingCount(Study s) {
        if (s.getStatus() != StudyStatus.RECRUITING) return null;
        return applications.countByStudyIdAndStatus(s.getId(), ApplicationStatus.PENDING);
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

    // ── 생애축 전이 (스터디장 or STUDY_EDIT) ──

    @Transactional
    public void closeRecruiting(String studyId) {
        Study s = loadStudy(studyId);
        requireState(s, StudyStatus.RECRUITING);
        s.closeRecruiting();
    }

    @Transactional
    public void finish(String studyId) {
        Study s = loadStudy(studyId);
        requireState(s, StudyStatus.ONGOING);
        s.finish();
    }

    /**
     * 전이는 한 칸씩만 간다. 건너뛰거나 되돌리는 것은 임원의 일괄 편집으로만 한다 —
     * 되돌릴 손이 하나 있으면 되고, 두 군데에 두면 규칙이 두 벌이 된다.
     */
    private void requireState(Study s, StudyStatus required) {
        if (s.getStatus() != required) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "지금 상태에서는 할 수 없는 동작입니다.");
        }
    }

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

    /**
     * 그 스터디의 신청 목록. ① 이 스터디장에게 승인·반려 손잡이는 주고 목록은 주지
     * 않아, 누구를 승인할지 모르는 채로 승인 버튼만 있었다.
     *
     * 반려는 싣지 않는다. 스터디장이 이미 내린 판단이고, 다시 보여 주면 그 목록이
     * 길어지기만 한다 — 신청자 본인은 자기 '내 스터디'에서 반려 사유를 본다.
     */
    @Transactional(readOnly = true)
    public StudyApplicantList applicantsOf(String studyId) {
        loadStudy(studyId);
        return new StudyApplicantList(
                entries(studyId, ApplicationStatus.PENDING, true),
                entries(studyId, ApplicationStatus.APPROVED, false));
    }

    private List<StudyApplicantEntry> entries(String studyId, ApplicationStatus status,
                                              boolean withMotive) {
        List<StudyApplication> rows = applications.findByStudyIdAndStatus(studyId, status);
        Map<String, Member> byId = members.findAllById(
                        rows.stream().map(StudyApplication::getApplicantId).toList()).stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        return rows.stream()
                .filter(a -> byId.containsKey(a.getApplicantId()))
                .sorted(Comparator.comparing(StudyApplication::getCreatedAt))
                .map(a -> {
                    Member m = byId.get(a.getApplicantId());
                    return new StudyApplicantEntry(a.getId(), m.getName(), m.getGen(),
                            withMotive ? a.getMotive() : null);
                })
                .toList();
    }

    /**
     * 반려된 자기 신청을 하드 삭제한다(D11). (studyId, applicantId) 유니크가 풀려
     * 재신청이 열린다 — deriveApply 가 신청 기록을 보고 CLOSED 를 내던 것이 기록이
     * 사라지면 저절로 OPEN 이 된다. 새 분기가 생기지 않는다.
     */
    @Transactional
    public void deleteApplication(String applicationId) {
        StudyApplication a = applications.findById(applicationId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "신청을 찾을 수 없습니다."));
        if (a.getStatus() != ApplicationStatus.REJECTED) {
            // 승인된 신청을 본인이 지울 수 있으면 그것은 탈퇴이고, 탈퇴는 이 단계에 없다.
            throw new ApiException(HttpStatus.CONFLICT, "NOT_REJECTED",
                    "반려된 신청만 삭제할 수 있습니다.");
        }
        applications.delete(a);
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
