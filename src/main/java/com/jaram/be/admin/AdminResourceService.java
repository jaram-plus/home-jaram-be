package com.jaram.be.admin;

import com.jaram.be.admin.AdminBatchExecutor.*;
import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.admin.dto.AdminBatchResponse;
import com.jaram.be.admin.dto.AdminBatchResponse.*;
import com.jaram.be.admin.dto.AdminListResponse;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberCareer;
import com.jaram.be.member.MemberGrade;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.member.MemberTerm;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.seminar.SeminarService;
import com.jaram.be.seminar.dto.SeminarResponse;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * UC-A1/A2: 관리 목록 + 일괄 저장. items 행은 리소스별 필드 맵(enum은 wire UPPER 키).
 * 정확한 행 스키마·검증 규칙은 FE admin.data SCHEMAS 미확정(§5) — 엔티티 기준 합리적
 * 투영/화이트리스트로 구현. batch는 행별 독립 tx(AdminBatchExecutor)로 부분 성공을 보장.
 */
@Service
public class AdminResourceService {

    private final MemberRepository members;
    private final SeminarRepository seminars;
    private final StudyRepository studies;
    private final AdminBatchExecutor executor;
    private final SeminarService seminarService;

    public AdminResourceService(MemberRepository members, SeminarRepository seminars,
                                StudyRepository studies, AdminBatchExecutor executor,
                                SeminarService seminarService) {
        this.members = members;
        this.seminars = seminars;
        this.studies = studies;
        this.executor = executor;
        this.seminarService = seminarService;
    }

    // ── A1: 목록 ──
    @Transactional(readOnly = true)
    public AdminListResponse list(AdminResource resource, String tab, String q, String sort,
                                  int page, int size) {
        List<Map<String, Object>> rows = switch (resource) {
            case members -> members.findAll().stream()
                    .filter(m -> m.getPurgedAt() == null)   // 파기된 회원은 관리 표에도 내지 않는다
                    .filter(m -> matchesMemberTab(m, tab))
                    .map(this::memberRow).toList();
            case seminars -> seminars.findAllByOrderByStartsAtDesc().stream()
                    .map(this::seminarRow).toList();
            case studies -> studies.findAll().stream()
                    .map(this::studyRow).toList();
        };

        List<Map<String, Object>> filtered = rows.stream()
                .filter(r -> matchesQuery(r, q))
                .sorted(comparator(sort))
                .toList();

        int total = filtered.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(total, from + size);
        List<Map<String, Object>> pageItems = from >= total ? List.of() : filtered.subList(from, to);
        return new AdminListResponse(pageItems, page, size, total);
    }

    // ── A2: 일괄 저장 (행별 독립 tx로 부분 성공) ──
    // 이 메서드 자체는 트랜잭션을 열지 않는다 — 각 행을 executor 의 REQUIRES_NEW 경계에서
    // 커밋해, 한 행의 충돌이 다른 행의 성공을 되돌리지 않게 한다.
    public AdminBatchResponse batch(AdminResource resource, AdminBatchRequest req) {
        List<Updated> updated = new ArrayList<>();
        List<Created> created = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();

        for (AdminBatchRequest.Update u : req.updates()) {
            switch (executor.updateRow(resource, u)) {
                case Applied a -> updated.add(new Updated(a.id()));
                case Conflicted c -> conflicts.add(new Conflict(c.id(), c.message()));
                case Invalid i -> errors.add(new RowError(i.id(), i.fieldErrors()));
            }
        }
        for (AdminBatchRequest.Create c : req.creates()) {
            switch (executor.createRow(resource, c)) {
                case CreateOk ok -> created.add(new Created(ok.tempId(), ok.id()));
                case CreateFailed f -> errors.add(new RowError(f.tempId(), f.fieldErrors()));
            }
        }
        for (String id : req.deletes()) {
            switch (executor.deleteRow(resource, id)) {
                case Deleted d -> deleted.add(d.id());
                case DeleteFailed f -> errors.add(new RowError(f.id(), f.fieldErrors()));
            }
        }
        return new AdminBatchResponse(updated, created, deleted, conflicts, errors);
    }

    // 내보내기(A5)용: 페이지 없이 리소스 전체 행을 투영해 반환.
    @Transactional(readOnly = true)
    public List<Map<String, Object>> allRows(AdminResource resource) {
        return switch (resource) {
            case members -> members.findAll().stream()
                    .filter(m -> m.getPurgedAt() == null)
                    .map(this::memberRow).toList();
            case seminars -> seminars.findAllByOrderByStartsAtDesc().stream().map(this::seminarRow).toList();
            case studies -> studies.findAll().stream().map(this::studyRow).toList();
        };
    }

    // ── 행 투영 ──

    private Map<String, Object> memberRow(Member m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("name", m.getName());
        r.put("studentId", m.getStudentId());
        r.put("email", m.getEmail());
        r.put("grade", m.getGrade() == null ? null : m.getGrade().name());
        r.put("status", m.getStatus().name());
        r.put("approval", m.getApproval().name());
        r.put("department", m.getDepartment() == null ? null : m.getDepartment().name());
        r.put("title", m.getTitle() == null ? null : m.getTitle().name());
        r.put("gen", m.getGen());
        r.put("faculty", m.getFaculty());
        // 임원 지정 화면이 임기 시작 기수를 보여준다. 현직이 없으면 null.
        r.put("termStartGen", m.currentTerm().map(MemberTerm::getStartGen).orElse(null));
        r.put("contributor", m.isContributor());
        // 기여자 표의 「직책 이력」 — 현직이 있으면 현직, 없으면 마지막으로 끝난 임기.
        MemberTerm last = m.currentTerm().or(m::lastEndedTerm).orElse(null);
        r.put("termDepartment", last == null ? null : last.getDepartment().name());
        r.put("termTitle", last == null ? null : last.getTitle().name());
        r.put("termEndGen", last == null ? null : last.getEndGen());
        // 졸업생 표의 「졸업연도·현재 소속·직무」 — 소속·직무는 가장 최근 이력에서 파생한다.
        r.put("gradYear", m.getGradYear());
        r.put("org", m.latestCareer().map(MemberCareer::getOrg).orElse(null));
        r.put("job", m.latestCareer().map(MemberCareer::getJob).orElse(null));
        r.put("version", m.getVersion());
        return r;
    }

    /**
     * 세미나 관리 화면의 행. 표는 이 중 다섯 칸(세미나명·발표자·일시·장소·상태)만 보여주고,
     * 나머지는 상세 모달이 쓴다. 상태·표시용 날짜는 저장값이 아니라 파생값이라 공개 응답과
     * 같은 계산(SeminarService.toResponse)을 그대로 빌려 쓴다 — 두 화면이 갈리지 않게.
     * attendanceCode는 여기서만 나간다 — 임원 전용 경로(/api/admin/**)이기 때문이다.
     */
    private Map<String, Object> seminarRow(Seminar s) {
        // 일시가 비면 상태도 표시용 날짜도 파생할 수 없다(배치 생성은 startsAt 없이도 통과한다).
        SeminarResponse v = s.getStartsAt() == null ? null : seminarService.toResponse(s, null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.getId());
        r.put("title", s.getTitle());
        r.put("speaker", s.getSpeaker());
        r.put("topic", s.getTopic());
        r.put("startsAt", v == null ? null : v.startsAt());
        r.put("day", v == null ? null : v.day());
        r.put("month", v == null ? null : v.month());
        r.put("weekday", v == null ? null : v.weekday());
        r.put("time", v == null ? null : v.time());
        r.put("place", s.getPlace());
        r.put("mode", s.getMode());
        r.put("status", v == null ? null : v.status().name());
        r.put("description", s.getDescription());
        r.put("materialUrl", s.getMaterialUrl());
        r.put("attendanceCode", s.getAttendanceCode());
        r.put("attendanceClosesAt", v == null ? null : v.attendanceClosesAt());
        r.put("attendanceClosedAt", s.getAttendanceClosedAt() == null ? null : s.getAttendanceClosedAt().toString());
        r.put("capacity", s.getCapacity());
        r.put("version", s.getVersion());
        return r;
    }

    private Map<String, Object> studyRow(Study s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.getId());
        r.put("title", s.getTitle());
        r.put("fields", s.getFields());
        r.put("leaderId", s.getLeaderId());
        r.put("capacity", s.getCapacity());
        r.put("approvalStatus", s.getApprovalStatus().name());
        r.put("createdAt", s.getCreatedAt().toString());
        r.put("version", s.getVersion());
        return r;
    }

    private boolean matchesMemberTab(Member m, String tab) {
        if (tab == null || tab.isBlank() || tab.equals("member")) return true;
        return switch (tab) {
            case "exec" -> m.currentTerm().isPresent();
            case "contrib" -> m.isContributor();
            case "grad" -> m.getGrade() == MemberGrade.OB;
            default -> true;
        };
    }

    private boolean matchesQuery(Map<String, Object> row, String q) {
        if (q == null || q.isBlank()) return true;
        String needle = q.toLowerCase();
        Object name = row.getOrDefault("name", row.get("title"));
        return name != null && name.toString().toLowerCase().contains(needle);
    }

    // "field,dir" (기본 asc). 두 값이 Number면 수치 비교, 아니면 String 비교. nulls last.
    private Comparator<Map<String, Object>> comparator(String sort) {
        if (sort == null || sort.isBlank()) return (a, b) -> 0;
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        boolean desc = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
        Comparator<Map<String, Object>> cmp = (a, b) -> compareValues(a.get(field), b.get(field));
        return desc ? cmp.reversed() : cmp;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareValues(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return 1;    // nulls last
        if (b == null) return -1;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue());
        }
        if (a.getClass() == b.getClass() && a instanceof Comparable ca) {
            return ((Comparable) ca).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }
}
