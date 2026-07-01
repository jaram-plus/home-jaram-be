package com.jaram.be.admin.dto;

// 계약 DriveExportResult. 생성된 스프레드시트 링크 + 파일 ID.
public record DriveExportResult(String fileUrl, String fileId) {
}
