package com.jaram.be.admin;

import com.jaram.be.admin.dto.DriveExportRequest;
import com.jaram.be.admin.dto.DriveExportResult;
import com.jaram.be.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * UC-A5: 현재 관리 목록을 Google Drive 스프레드시트로 내보내기. 리소스 행을 모아
 * {@link DriveExporter} 포트에 위임한다(기본 스텁, 실제 어댑터는 §5 확정 후 교체).
 */
@Service
public class AdminExportService {

    private final AdminResourceService resources;
    private final DriveExporter exporter;

    public AdminExportService(AdminResourceService resources, DriveExporter exporter) {
        this.resources = resources;
        this.exporter = exporter;
    }

    public DriveExportResult export(DriveExportRequest req) {
        AdminResource resource = parseResource(req.resource());
        List<Map<String, Object>> rows = resources.allRows(resource);
        return exporter.export(req.resource(), rows, req.columns());
    }

    private AdminResource parseResource(String raw) {
        try {
            return AdminResource.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION", "입력값을 확인해 주세요.",
                    Map.of("resource", "members/seminars/studies 중 하나여야 합니다."));
        }
    }
}
