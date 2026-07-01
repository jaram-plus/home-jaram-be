package com.jaram.be.admin;

import com.jaram.be.admin.dto.DriveExportResult;
import java.util.List;
import java.util.Map;

/**
 * 관리 목록을 Google Drive 스프레드시트로 내보내는 포트. 실제 어댑터(서비스 계정 vs
 * OAuth)는 인증 방식 확정 후 주입 교체(§5). 기본 구현은 {@link StubDriveExporter}.
 */
public interface DriveExporter {

    /**
     * @param resourceName 리소스 경로(members/seminars/studies)
     * @param rows         투영된 행
     * @param columns      내보낼 열(null이면 전체)
     * @return 생성 파일의 링크/ID
     */
    DriveExportResult export(String resourceName, List<Map<String, Object>> rows, List<String> columns);
}
