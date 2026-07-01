package com.jaram.be.admin;

import com.jaram.be.admin.dto.DriveExportResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drive 내보내기 스파이크 어댑터. 행→선택 열까지 실제 조립(데이터 경로 검증)하지만
 * 업로드는 수행하지 않고 결정적 스텁 결과를 돌려준다 — 실제 Google Drive 클라이언트는
 * 인증 방식(서비스 계정 vs OAuth)·driveFolder 지정 확정 후 별도 어댑터로 교체(§5).
 * 교체 시 실제 어댑터를 {@code @Primary} 로 등록하거나 이 빈을 제거한다.
 */
@Component
public class StubDriveExporter implements DriveExporter {

    @Override
    public DriveExportResult export(String resourceName, List<Map<String, Object>> rows, List<String> columns) {
        // 선택 열로 시트 데이터를 조립(실제 어댑터가 동일 입력으로 업로드할 형태). 업로드는 생략.
        List<List<Object>> sheet = assemble(rows, columns);

        String fileId = "stub-" + resourceName + "-" + UUID.randomUUID();
        // 스텁 호스트 — 실제 Drive 링크가 아님을 URL로 명시.
        String fileUrl = "https://drive.stub.local/" + fileId + "?rows=" + sheet.size();
        return new DriveExportResult(fileUrl, fileId);
    }

    // columns가 없으면 첫 행의 키 순서를 헤더로. 각 행을 헤더 순서대로 값 리스트화.
    private List<List<Object>> assemble(List<Map<String, Object>> rows, List<String> columns) {
        List<String> header = (columns == null || columns.isEmpty())
                ? (rows.isEmpty() ? List.of() : List.copyOf(rows.get(0).keySet()))
                : columns;
        return rows.stream()
                .map(r -> header.stream().map(r::get).toList())
                .toList();
    }
}
