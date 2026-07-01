package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.member.*;
import com.jaram.be.seminar.Attendance;
import com.jaram.be.seminar.AttendanceRepository;
import com.jaram.be.seminar.Seminar;
import com.jaram.be.seminar.SeminarRepository;
import com.jaram.be.study.Study;
import com.jaram.be.study.StudyApplication;
import com.jaram.be.study.StudyApplicationRepository;
import com.jaram.be.study.StudyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 관리 일괄 저장의 행 단위 실행기. 각 행을 REQUIRES_NEW 로 독립 커밋하므로,
 * 한 행의 커밋 시점 낙관적 잠금(@Version) 충돌·무결성 위반이 다른 행의 성공을
 * 되돌리지 않는다 — 계약의 "부분 성공"을 실제로 보장. 성공 행은 flush 로 커밋
 * 확정 후 결과를 반환하고, 호출측(AdminResourceService)이 응답에 누적한다.
 */
@Service
public class AdminBatchExecutor {

    private final MemberRepository members;
    private final SeminarRepository seminars;
    private final AttendanceRepository attendances;
    private final StudyRepository studies;
    private final StudyApplicationRepository applications;

    public AdminBatchExecutor(MemberRepository members, SeminarRepository seminars,
                              AttendanceRepository attendances, StudyRepository studies,
                              StudyApplicationRepository applications) {
        this.members = members;
        this.seminars = seminars;
        this.attendances = attendances;
        this.studies = studies;
        this.applications = applications;
    }

    // ── 행 결과 타입 ──
    public sealed interface UpdateOutcome permits Applied, Conflicted, Invalid {}
    public record Applied(String id) implements UpdateOutcome {}
    public record Conflicted(String id, String message) implements UpdateOutcome {}
    public record Invalid(String id, Map<String, String> fieldErrors) implements UpdateOutcome {}

    public sealed interface CreateOutcome permits CreateOk, CreateFailed {}
    public record CreateOk(String tempId, String id) implements CreateOutcome {}
    public record CreateFailed(String tempId, Map<String, String> fieldErrors) implements CreateOutcome {}

    public sealed interface DeleteOutcome permits Deleted, DeleteFailed {}
    public record Deleted(String id) implements DeleteOutcome {}
    public record DeleteFailed(String id, Map<String, String> fieldErrors) implements DeleteOutcome {}

    // ── 업데이트 ──
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UpdateOutcome updateRow(AdminResource resource, AdminBatchRequest.Update u) {
        Long current = versionOf(resource, u.id());
        if (current == null) return new Invalid(u.id(), Map.of("id", "대상을 찾을 수 없습니다."));
        if (u.version() != null && u.version().longValue() != current) {
            return new Conflicted(u.id(), "다른 사용자가 먼저 수정했습니다.");
        }
        Map<String, Object> f = u.fields() == null ? Map.of() : u.fields();
        Map<String, String> fe = switch (resource) {
            case members -> updateMember(members.findById(u.id()).orElseThrow(), f);
            case seminars -> updateSeminar(seminars.findById(u.id()).orElseThrow(), f);
            case studies -> updateStudy(studies.findById(u.id()).orElseThrow(), f);
        };
        if (!fe.isEmpty()) return new Invalid(u.id(), fe);
        try {
            flush(resource);   // 커밋 시점 잠금/무결성 충돌을 여기서 표면화
        } catch (ObjectOptimisticLockingFailureException e) {
            return new Conflicted(u.id(), "다른 사용자가 먼저 수정했습니다.");
        } catch (DataIntegrityViolationException e) {
            return new Invalid(u.id(), Map.of("id", "저장 중 제약 조건을 위반했습니다."));
        }
        return new Applied(u.id());
    }

    // ── 생성 ──
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CreateOutcome createRow(AdminResource resource, AdminBatchRequest.Create c) {
        Map<String, Object> f = c.fields() == null ? Map.of() : c.fields();
        Map<String, String> errors = new LinkedHashMap<>();
        String newId = create(resource, f, errors);
        if (newId == null || !errors.isEmpty()) return new CreateFailed(c.tempId(), errors);
        try {
            flush(resource);
        } catch (DataIntegrityViolationException e) {
            return new CreateFailed(c.tempId(), Map.of("id", "저장 중 제약 조건을 위반했습니다."));
        }
        return new CreateOk(c.tempId(), newId);
    }

    // ── 삭제 ──
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeleteOutcome deleteRow(AdminResource resource, String id) {
        Map<String, String> fe = delete(resource, id);
        if (!fe.isEmpty()) return new DeleteFailed(id, fe);
        return new Deleted(id);
    }

    // ── 내부: 버전/플러시 ──
    private Long versionOf(AdminResource resource, String id) {
        return switch (resource) {
            case members -> members.findById(id).map(Member::getVersion).orElse(null);
            case seminars -> seminars.findById(id).map(Seminar::getVersion).orElse(null);
            case studies -> studies.findById(id).map(Study::getVersion).orElse(null);
        };
    }

    private void flush(AdminResource resource) {
        switch (resource) {
            case members -> members.flush();
            case seminars -> seminars.flush();
            case studies -> studies.flush();
        }
    }

    // ── 내부: 리소스별 업데이트 (validate-all-then-apply) ──
    private Map<String, String> updateMember(Member m, Map<String, Object> f) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Runnable> actions = new ArrayList<>();
        f.forEach((k, v) -> {
            switch (k) {
                case "name" -> actions.add(() -> m.setName(str(v)));
                case "gen" -> intField(v, errors, k, m::setGen, actions);
                case "grade" -> enumField(MemberGrade.class, v, errors, k, m::setGrade, actions, false);
                case "status" -> enumField(MemberStatus.class, v, errors, k, m::setStatus, actions, false);
                case "approval" -> enumField(MemberApproval.class, v, errors, k, m::setApproval, actions, false);
                case "department" -> enumField(MemberDepartment.class, v, errors, k, m::setDepartment, actions, true);
                case "title" -> enumField(MemberTitle.class, v, errors, k, m::setTitle, actions, true);
                default -> errors.put(k, "수정할 수 없는 필드입니다.");
            }
        });
        if (errors.isEmpty()) actions.forEach(Runnable::run);
        return errors;
    }

    private Map<String, String> updateSeminar(Seminar s, Map<String, Object> f) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Runnable> actions = new ArrayList<>();
        f.forEach((k, v) -> {
            switch (k) {
                case "title" -> actions.add(() -> s.setTitle(str(v)));
                case "speaker" -> actions.add(() -> s.setSpeaker(str(v)));
                case "topic" -> actions.add(() -> s.setTopic(str(v)));
                case "place" -> actions.add(() -> s.setPlace(str(v)));
                case "mode" -> actions.add(() -> s.setMode(str(v)));
                case "capacity" -> intField(v, errors, k, s::setCapacity, actions);
                default -> errors.put(k, "수정할 수 없는 필드입니다.");
            }
        });
        if (errors.isEmpty()) actions.forEach(Runnable::run);
        return errors;
    }

    private Map<String, String> updateStudy(Study s, Map<String, Object> f) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<Runnable> actions = new ArrayList<>();
        f.forEach((k, v) -> {
            switch (k) {
                case "title" -> actions.add(() -> s.setTitle(str(v)));
                case "capacity" -> intField(v, errors, k, s::setCapacity, actions);
                default -> errors.put(k, "수정할 수 없는 필드입니다.");
            }
        });
        if (errors.isEmpty()) actions.forEach(Runnable::run);
        return errors;
    }

    // ── 내부: 생성 ──
    private String create(AdminResource resource, Map<String, Object> f, Map<String, String> errors) {
        switch (resource) {
            case members -> {
                errors.put("resource", "회원은 일괄 생성이 지원되지 않습니다. 가입 절차를 사용하세요.");
                return null;
            }
            case seminars -> {
                if (isBlank(f.get("title"))) { errors.put("title", "필수입니다."); return null; }
                Seminar s = Seminar.create(str(f.get("title")), str(f.get("speaker")), str(f.get("topic")),
                        parseInstant(f.get("startsAt")), str(f.get("place")), str(f.get("mode")),
                        str(f.get("attendanceCode")), str(f.get("materialUrl")),
                        asInt(f.get("capacity")), null);
                return seminars.save(s).getId();
            }
            case studies -> {
                if (isBlank(f.get("title"))) { errors.put("title", "필수입니다."); return null; }
                if (isBlank(f.get("leaderId"))) { errors.put("leaderId", "필수입니다."); return null; }
                Integer cap = asInt(f.get("capacity"));
                if (cap == null) { errors.put("capacity", "필수입니다."); return null; }
                Study s = Study.create(str(f.get("title")), asStringList(f.get("fields")), cap,
                        str(f.get("schedule")), str(f.get("period")), str(f.get("mode")),
                        str(f.get("intro")), str(f.get("leaderId")));
                return studies.save(s).getId();
            }
        }
        return null;
    }

    // ── 내부: 삭제 (참조 정리/차단) ──
    private Map<String, String> delete(AdminResource resource, String id) {
        Map<String, String> errors = new LinkedHashMap<>();
        switch (resource) {
            case members -> {
                if (members.findById(id).isEmpty()) { errors.put("id", "대상을 찾을 수 없습니다."); return errors; }
                if (!studies.findByLeaderIdOrderByCreatedAtDesc(id).isEmpty()) {
                    errors.put("id", "스터디 리더인 회원은 삭제할 수 없습니다.");
                    return errors;
                }
                applications.deleteAll(applications.findByApplicantIdOrderByCreatedAtDesc(id));
                attendances.deleteAll(attendances.findByMemberId(id));
                members.deleteById(id);
            }
            case seminars -> {
                if (seminars.findById(id).isEmpty()) { errors.put("id", "대상을 찾을 수 없습니다."); return errors; }
                attendances.deleteAll(attendances.findBySeminarIdOrderByAtAsc(id));
                seminars.deleteById(id);
            }
            case studies -> {
                if (studies.findById(id).isEmpty()) { errors.put("id", "대상을 찾을 수 없습니다."); return errors; }
                applications.deleteAll(applications.findByStudyId(id));
                studies.deleteById(id);
            }
        }
        return errors;
    }

    // ── 값 변환 헬퍼 ──
    private String str(Object v) { return v == null ? null : v.toString(); }

    private boolean isBlank(Object v) { return v == null || v.toString().isBlank(); }

    private Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object v) {
        if (v instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of();
    }

    private Instant parseInstant(Object v) {
        if (v == null) return null;
        try { return Instant.parse(v.toString()); } catch (Exception e) { return null; }
    }

    private void intField(Object v, Map<String, String> errors, String key,
                          Consumer<Integer> setter, List<Runnable> actions) {
        Integer i = asInt(v);
        if (i == null) errors.put(key, "정수가 아닙니다.");
        else actions.add(() -> setter.accept(i));
    }

    private <E extends Enum<E>> void enumField(Class<E> type, Object v, Map<String, String> errors,
                                               String key, Consumer<E> setter, List<Runnable> actions,
                                               boolean nullable) {
        if (v == null) {
            if (nullable) actions.add(() -> setter.accept(null));
            else errors.put(key, "필수입니다.");
            return;
        }
        try {
            E e = Enum.valueOf(type, v.toString());
            actions.add(() -> setter.accept(e));
        } catch (IllegalArgumentException ex) {
            errors.put(key, "허용되지 않은 값입니다.");
        }
    }
}
