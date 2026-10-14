package com.jaram.be.seminar;

import com.jaram.be.member.Member;
import com.jaram.be.support.Actors;
import com.jaram.be.support.PostgresTest;
import com.jaram.be.security.authz.Role;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SeminarDetailTest extends PostgresTest {

    @LocalServerPort int port;
    @Autowired SeminarRepository seminars;
    @Autowired AttendanceRepository attendances;
    @Autowired Actors actors;

    @BeforeEach void setup() {
        RestAssured.port = port;
        attendances.deleteAll();
        seminars.deleteAll();
    }

    private Seminar save(String title, String owner, java.util.function.Consumer<Seminar> mut) {
        Seminar s = Seminar.create(title, null, null, Instant.now(),
                null, null, "CODE", null, null, owner);
        mut.accept(s);
        return seminars.save(s);
    }

    @Test
    void approvedVisibleToAnyMember() {
        Seminar s = save("공개", "officer-1", Seminar::approve);
        String member = actors.member();
        given().header("Authorization", "Bearer " + member)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("title", equalTo("공개"))
                .body("approvalStatus", equalTo("APPROVED"));
    }

    @Test
    void anonymousGets401() {
        Seminar s = save("공개", "officer-1", Seminar::approve);
        given().when().get("/api/seminars/" + s.getId()).then().statusCode(401);
    }

    @Test
    void pendingHiddenFromStrangerAs404() {
        Seminar s = save("대기", "owner-1", x -> {}); // PENDING
        String other = actors.member();
        given().header("Authorization", "Bearer " + other)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(404);
    }

    @Test
    void pendingVisibleToOwner() {
        Member owner = actors.save(Role.MEMBER);
        Seminar s = save("대기", owner.getId(), x -> {});
        String ownerToken = actors.tokenFor(owner);
        given().header("Authorization", "Bearer " + ownerToken)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("approvalStatus", equalTo("PENDING"));
    }

    @Test
    void rejectedVisibleToOfficer() {
        Seminar s = save("반려", "owner-1", x -> x.reject("사유"));
        String officer = actors.officer();
        given().header("Authorization", "Bearer " + officer)
                .when().get("/api/seminars/" + s.getId()).then().statusCode(200)
                .body("rejectReason", equalTo("사유"));
    }

    @Test
    void missingReturns404() {
        String member = actors.member();
        given().header("Authorization", "Bearer " + member)
                .when().get("/api/seminars/nope").then().statusCode(404);
    }
}
