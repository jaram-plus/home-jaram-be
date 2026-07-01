package com.jaram.be.admin;

import com.jaram.be.admin.AdminBatchExecutor.*;
import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.admin.dto.AdminBatchResponse;
import com.jaram.be.admin.dto.AdminBatchResponse.*;
import com.jaram.be.admin.dto.AdminListResponse;
import com.jaram.be.member.Member;
import com.jaram.be.member.MemberCategory;
import com.jaram.be.member.MemberRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
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

    public AdminResourceService(MemberRepository members, SeminarRepository seminars,
                                StudyRepository studies, AdminBatchExecutor executor) {
        this.members = members;
        this.seminars = seminars;
        this.studies = studies;
        this.executor = executor;
    }

    // ── A1: 목록 ──
    @Transactional(readOnly = true)
    public AdminListResponse list(AdminResource resource, String tab, String q, String sort,
                                  int page, int size) {
        List<Map<String, Object>> rows = switch (resource) {
            case members -> members.findAll().stream()
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
        r.put("categories", m.getCategories().stream().map(Enum::name).toList());
        r.put("version", m.getVersion());
        return r;
    }

    private Map<String, Object> seminarRow(Seminar s) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", s.getId());
        r.put("title", s.getTitle());
        r.put("speaker", s.getSpeaker());
        r.put("topic", s.getTopic());
        r.put("startsAt", s.getStartsAt() == null ? null : s.getStartsAt().toString());
        r.put("place", s.getPlace());
        r.put("mode", s.getMode());
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
            case "exec" -> m.hasCategory(MemberCategory.exec);
            case "contrib" -> m.hasCategory(MemberCategory.contrib);
            case "graduate" -> m.hasCategory(MemberCategory.grad);
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
