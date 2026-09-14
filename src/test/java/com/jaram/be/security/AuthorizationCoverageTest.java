package com.jaram.be.security;

import com.jaram.be.support.PostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모든 핸들러가 @PreAuthorize 를 갖거나, 아래 두 집합에 이름이 적혀 있어야 한다.
 *
 * 예전 판은 /api/admin 으로 시작하는 핸들러만 봤다. 그래서 스터디의 상태 전이처럼
 * /api/admin 밖에 있는 권한 동작은 그물에 걸리지 않았고, 애너테이션을 빠뜨리면
 * SecurityConfig 의 anyRequest().authenticated() 때문에 "로그인한 아무나"가 됐다.
 *
 * 지켜야 할 경로를 열거하는 방식은 고르지 않았다 — 애너테이션을 잊는 것과 똑같이
 * 목록을 잊을 수 있어서, 잊었을 때 조용한 성질이 그대로 남는다. 기본값을 뒤집어
 * "권한이 필요 없는 것"을 적게 하면, 새 엔드포인트는 기본적으로 이 테스트를 깬다.
 * 작성자는 게이트를 달거나 아래에 한 줄을 더하면서 그 선택을 눈으로 확인하게 된다.
 *
 * 이 두 집합은 그대로 "누가 권한 없이 통과하는가"의 표다.
 */
@SpringBootTest
class AuthorizationCoverageTest extends PostgresTest {

    /** SecurityConfig 가 permitAll 로 여는 것들. 비로그인 방문자가 본다. */
    private static final Set<String> PUBLIC = Set.of(
            "AuthController#login",
            "AuthController#signup",
            "AuthController#resetRequest",
            "AuthController#resetConfirm",
            "PeopleController#list",
            "SeminarController#list",
            "StudyController#list",
            "ScheduleController#list",
            "SiteLinksController#get");

    /** 로그인만으로 통과하는 것이 의도인 핸들러. 권한이 아니라 1층 자격이 가른다. */
    private static final Set<String> AUTHENTICATED_ONLY = Set.of(
            "MeController#get",
            "MeController#update",
            "MeController#withdraw",
            "MeController#reregister",
            "ScheduleController#claim",
            "ScheduleController#cancel",
            "ScheduleController#submit",
            "SeminarController#getOne",
            "SeminarController#attend",
            "SeminarController#attendees",
            "StudyController#detail",
            "StudyController#create",
            "StudyController#apply",
            "StudyController#my");

    @Autowired RequestMappingHandlerMapping mapping;

    @Test
    void everyHandlerIsEitherGatedOrExplicitlyListed() {
        List<String> ungated = new ArrayList<>();

        mapping.getHandlerMethods().forEach((info, method) -> {
            String name = method.getBeanType().getSimpleName() + "#" + method.getMethod().getName();
            if (!method.getBeanType().getPackageName().startsWith("com.jaram.be")) return;

            boolean declared = method.getMethodAnnotation(PreAuthorize.class) != null
                    || method.getBeanType().getAnnotation(PreAuthorize.class) != null;
            if (declared || PUBLIC.contains(name) || AUTHENTICATED_ONLY.contains(name)) return;

            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            ungated.add(name + " " + patterns);
        });

        assertThat(ungated)
                .as("@PreAuthorize 도 없고 목록에도 없는 핸들러 — 로그인한 아무나 쓸 수 있게 된다. "
                        + "게이트를 달거나, 의도한 것이라면 PUBLIC/AUTHENTICATED_ONLY 에 적어라")
                .isEmpty();
    }

    @Test
    void theListsDoNotRotIntoNamesThatNoLongerExist() {
        Set<String> live = new java.util.HashSet<>();
        mapping.getHandlerMethods().forEach((info, method) -> {
            if (!method.getBeanType().getPackageName().startsWith("com.jaram.be")) return;
            live.add(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName());
        });

        List<String> stale = new ArrayList<>();
        PUBLIC.forEach(n -> { if (!live.contains(n)) stale.add(n); });
        AUTHENTICATED_ONLY.forEach(n -> { if (!live.contains(n)) stale.add(n); });

        assertThat(stale).as("없어진 핸들러가 목록에 남아 있다 — 지워라").isEmpty();
    }
}
