package com.jaram.be.admin;

import com.jaram.be.admin.dto.AdminBatchRequest;
import com.jaram.be.admin.dto.AdminBatchResponse;
import com.jaram.be.admin.dto.AdminListResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class AdminResourceController {

    private final AdminResourceService service;

    public AdminResourceController(AdminResourceService service) { this.service = service; }

    // UC-A1: 관리 목록 (검색·필터·정렬·페이지). page 기본 1, size 기본 8.
    @GetMapping("/{resource}")
    public AdminListResponse list(@PathVariable AdminResource resource,
                                  @RequestParam(required = false) String tab,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "8") int size) {
        return service.list(resource, tab, q, sort, page, size);
    }

    // UC-A2: 변경분 일괄 저장 (부분 성공). 콜론 경로 {resource}:batch.
    @PatchMapping("/{resource}:batch")
    public AdminBatchResponse batch(@PathVariable AdminResource resource,
                                    @RequestBody AdminBatchRequest req) {
        return service.batch(resource, req);
    }
}
