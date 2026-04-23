package com.store.common.dto;

import lombok.Builder;
import org.slf4j.MDC;
import java.time.Instant;

@Builder
public record ApiResponse<T>(
    String correlationId,
    String status,
    T data,
    Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
            .correlationId(MDC.get("X-Correlation-ID"))
            .status("OK").data(data).timestamp(Instant.now()).build();
    }

    public static <T> ApiResponse<T> accepted(T data) {
        return ApiResponse.<T>builder()
            .correlationId(MDC.get("X-Correlation-ID"))
            .status("ACCEPTED").data(data).timestamp(Instant.now()).build();
    }
}
