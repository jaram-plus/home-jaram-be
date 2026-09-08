package com.jaram.be.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ScheduleRepository extends JpaRepository<Schedule, String> {
    List<Schedule> findAllByOrderByStartsAtAsc();

    // 세미나 삭제 시 슬롯의 역참조를 끊기 위해. 슬롯은 Schedule을 통해서만 저장한다.
    List<Schedule> findBySlotsSeminarId(String seminarId);

    // 회원 삭제 시 슬롯의 역참조를 끊기 위해. 슬롯은 Schedule을 통해서만 저장한다.
    List<Schedule> findBySlotsMemberId(String memberId);
}
