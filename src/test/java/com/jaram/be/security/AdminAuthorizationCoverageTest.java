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
 * /api/admin/** 의 모든 핸들러에 @PreAuthorize 가 붙어 있는지 본다.
 *
 * 권한 규칙이 URL 문자열에 있을 때는 매처를 빠뜨리면 조용히 열렸다. 이제는
 * 애너테이션을 빠뜨리면 "로그인한 아무나"가 된다 — 컴파일러가 잡지 못하는
 * 누락이라 테스트가 잡는다.
 */
@SpringBootTest
class AdminAuthorizationCoverageTest extends PostgresTest {

    @Autowired RequestMappingHandlerMapping mapping;

    @Test
    void everyAdminHandlerDeclaresPreAuthorize() {
        List<String> missing = new ArrayList<>();

        mapping.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = info.getPathPatternsCondition() == null
                    ? Set.of()
                    : info.getPathPatternsCondition().getPatternValues();
            if (patterns.stream().noneMatch(p -> p.startsWith("/api/admin"))) return;

            boolean declared = method.getMethodAnnotation(PreAuthorize.class) != null
                    || method.getBeanType().getAnnotation(PreAuthorize.class) != null;
            if (!declared) {
                missing.add(method.getBeanType().getSimpleName() + "#" + method.getMethod().getName()
                        + " " + patterns);
            }
        });

        assertThat(missing)
                .as("@PreAuthorize 가 없는 관리자 핸들러 — 로그인한 아무나 쓸 수 있게 된다")
                .isEmpty();
    }
}
