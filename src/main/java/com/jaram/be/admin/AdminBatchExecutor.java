package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.member.*;
import com.jaram.be.schedule.Schedule;
import com.jaram.be.schedule.ScheduleRepository;
import com.jaram.be.schedule.ScheduleSlot;
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
    private final ScheduleRepository schedules;
    private final AdminSettingsRepository settings;

    public AdminBatchExecutor(MemberRepository members, SeminarRepository seminars,
                              AttendanceRepository attendances, StudyRepository studies,
                              StudyApplicationRepository applications,
                              ScheduleRepository schedules,
                              AdminSettingsRepository settings) {
        this.members = members;
        this.seminars = seminars;
        this.attendances = attendances;
        this.studies = studies;
        this.applications = applications;
        this.schedules = schedules;
        this.settings = settings;
    }

    /** 임기 전환 기준 기수. 운영이 설정한 현재 기수를 우선하고, 미설정(0)이면 올해 기준으로 계산한다. */
    private int currentGen() {
        Integer c = settings.findById(AdminSettings.SINGLETON_ID)
                .map(AdminSettings::getCurrentCohort).orElse(null);
        return (c != null && c > 0) ? c : Gen.current();
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
                case "grade" -> enumField(MemberGrade.class, v, errors, k, g -> applyGrade(m, g), actions, false);
                case "status" -> enumField(MemberStatus.class, v, errors, k, m::setStatus, actions, false);
                case "approval" -> enumField(MemberApproval.class, v, errors, k, m::setApproval, actions, false);
                case "contributor" -> boolField(v, errors, k, m::setContributor, actions);
                case "department" -> enumCheck(MemberDepartment.class, v, errors, k);
                case "title" -> enumCheck(MemberTitle.class, v, errors, k);
                default -> errors.put(k, "수정할 수 없는 필드입니다.");
            }
        });
        // 졸업 규칙. 신입부원은 바로 OB 가 될 수 없고, 현직 임원과 OB 는 공존하지 않는다.
        MemberGrade newGrade = errors.isEmpty() && f.containsKey("grade")
                ? parsed(f.get("grade"), MemberGrade.class) : null;
        if (errors.isEmpty() && newGrade == MemberGrade.OB) {
            if (m.getGrade() == MemberGrade.NEWCOMER) {
                errors.put("grade", "신입부원은 바로 OB로 변경할 수 없습니다. 준회원 또는 정회원을 거쳐 주세요.");
            } else if (f.get("title") != null) {
                errors.put("grade", "OB로 변경하면서 직책을 함께 지정할 수 없습니다.");
            }
        }
        // 이미 OB 인 회원에게는 새 임기를 부여하지 않는다. 등급을 먼저 되돌려야 한다.
        if (errors.isEmpty() && f.get("title") != null
                && m.getGrade() == MemberGrade.OB && newGrade == null) {
            errors.put("title", "OB 회원에게는 직책을 지정할 수 없습니다. 등급을 먼저 변경해 주세요.");
        }
        // 직책×부서 조합 검사. 한쪽만 요청에 담겨 오면 나머지는 엔티티의 현재 값을 기준으로 판정한다.
        if (errors.isEmpty() && (f.containsKey("department") || f.containsKey("title"))) {
            MemberDepartment d = f.containsKey("department")
                    ? parsed(f.get("department"), MemberDepartment.class) : m.getDepartment();
            MemberTitle t = f.containsKey("title")
                    ? parsed(f.get("title"), MemberTitle.class) : m.getTitle();
            String comboError = comboError(d, t);
            if (comboError != null) {
                errors.put("title", comboError);
            } else {
                actions.add(t == null ? () -> m.endCurrentTerm(currentGen())
                                      : () -> m.assignTerm(d, t, currentGen()));
            }
        }
        if (errors.isEmpty()) actions.forEach(Runnable::run);
        return errors;
    }

    /** department/title 은 임기로 함께 적용되므로 개별 setter 가 없다. 값 검증만 여기서 한다. */
    private <E extends Enum<E>> void enumCheck(Class<E> type, Object v,
                                               Map<String, String> errors, String key) {
        if (v == null) return;   // null = 해제, 허용
        try {
            Enum.valueOf(type, v.toString());
        } catch (IllegalArgumentException e) {
            errors.put(key, "허용되지 않은 값입니다.");
        }
    }

    /** OB 로 전환하면 현직 임원 자격이 끝난다. 임기 이력은 남는다("전 학술부장"). */
    private void applyGrade(Member m, MemberGrade g) {
        m.setGrade(g);
        if (g == MemberGrade.OB) m.endCurrentTerm(currentGen());
    }

    private <E extends Enum<E>> E parsed(Object v, Class<E> type) {
        return v == null ? null : Enum.valueOf(type, v.toString());
    }

    // null = 허용.
    private String comboError(MemberDepartment d, MemberTitle t) {
        if (t == null) return null;
        if (d == null) return "직책을 지정하려면 부서를 함께 지정해 주세요.";
        if (t.allowedIn(d)) return null;
        return switch (d) {
            case LEADERSHIP -> "회장단에는 회장 또는 부회장만 지정할 수 있습니다.";
            case ACADEMIC, PR, FINANCE -> d.label() + "에는 부장 또는 부원만 지정할 수 있습니다.";
            case INFRA -> "인프라에는 서버 관리자만 지정할 수 있습니다.";
        };
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
                case "description" -> actions.add(() -> s.setDescription(str(v)));
                case "materialUrl" -> actions.add(() -> s.setMaterialUrl(str(v)));
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
                // 슬롯이 없어진 세미나를 가리키면 취소도 재제출도 막힌다. 점유는 남기고 링크만 끊는다.
                for (Schedule sch : schedules.findBySlotsSeminarId(id)) {
                    sch.getSlots().stream().filter(x -> id.equals(x.getSeminarId()))
                            .forEach(ScheduleSlot::detachSeminar);
                    schedules.save(sch);
                }
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

    private void boolField(Object v, Map<String, String> errors, String key,
                           Consumer<Boolean> setter, List<Runnable> actions) {
        Boolean b = asBool(v);
        if (b == null) errors.put(key, "참/거짓이 아닙니다.");
        else actions.add(() -> setter.accept(b));
    }

    private Boolean asBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
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
