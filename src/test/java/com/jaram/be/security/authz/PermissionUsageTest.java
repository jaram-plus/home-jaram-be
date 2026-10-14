package com.jaram.be.security.authz;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R8.2 — 정의만 있고 어떤 판정에도 쓰이지 않는 Permission 상수를 잡는다.
 *
 * Spring 없이 src/main/java 를 읽는다(Gradle 테스트 작업 디렉터리 = 프로젝트 루트).
 * 주석을 지운 뒤 상수 이름을 단어 경계로 찾으므로, 주석에만 이름이 남은 상수는 통과하지 못한다.
 * 문자열 리터럴은 남긴다 — hasAuthority('X') 애너테이션 문자열이 곧 사용이기 때문이다.
 * Permission.java 와 Policy.java 는 정의·매트릭스일 뿐이라 제외한다.
 */
class PermissionUsageTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> EXCLUDED_FILES = List.of("Permission.java", "Policy.java");

    /** 판정에 쓰이지만 이름으로는 소스에 나타나지 않는 상수와 그 사유. */
    private static final Map<Permission, String> EXCEPTIONS = Map.of(
            Permission.TERM_ASSIGN,
            "Policy.canAssign 이 직책 부여 규칙으로 판정하고 AdminBatchExecutor 가 그것을 부른다. "
                    + "상수 이름을 직접 쓰는 곳이 없다");

    @Test
    void everyPermissionIsUsedOutsideDefinitionAndMatrix() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (EXCLUDED_FILES.contains(f.getFileName().toString())) continue;
                all.append(stripComments(Files.readString(f))).append('\n');
            }
        }
        String code = all.toString();

        List<String> unused = new ArrayList<>();
        for (Permission p : Permission.values()) {
            if (EXCEPTIONS.containsKey(p)) continue;
            if (!Pattern.compile("\\b" + p.name() + "\\b").matcher(code).find()) unused.add(p.name());
        }

        assertThat(unused)
                .as("Permission.java·Policy.java 밖에서 쓰이지 않는 상수 — 판정에 쓰거나 지워라. "
                        + "의도한 것이면 EXCEPTIONS 에 사유와 함께 적어라")
                .isEmpty();
    }

    @Test
    void exceptionsDoNotRot() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (EXCLUDED_FILES.contains(f.getFileName().toString())) continue;
                all.append(stripComments(Files.readString(f))).append('\n');
            }
        }
        String code = all.toString();
        EXCEPTIONS.forEach((p, reason) -> {
            assertThat(reason).isNotBlank();
            assertThat(Pattern.compile("\\b" + p.name() + "\\b").matcher(code).find())
                    .as(p + " 는 이제 코드에서 쓰인다 — 예외 목록에서 지워라")
                    .isFalse();
        });
    }

    @Test
    void stripCommentsRemovesCommentsButKeepsStrings() {
        String src = "a // X_LINE\nb /* X_BLOCK\n more */ c \"hasAuthority('X_STR') // not comment\" 'x' \"q\\\"/* y */\"";
        String out = stripComments(src);
        assertThat(out).doesNotContain("X_LINE", "X_BLOCK", "more");
        assertThat(out).contains("X_STR", "// not comment", "/* y */");
        assertThat(out).contains("a ", "b ", " c ");
    }

    /** 주석을 지우고 문자열·문자 리터럴(텍스트 블록 포함)은 그대로 둔다. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                while (end > 0 && s.charAt(end - 1) == '\\') end = s.indexOf("\"\"\"", end + 1);
                end = end < 0 ? n : end + 3;
                out.append(s, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') {
                    if (s.charAt(j) == '\\') j++;
                    j++;
                }
                j = Math.min(j + 1, n);
                out.append(s, i, j);
                i = j;
            } else if (s.startsWith("//", i)) {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
