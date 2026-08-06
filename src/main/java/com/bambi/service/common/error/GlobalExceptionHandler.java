package com.bambi.service.common.error;

import com.bambi.service.common.response.ApiResponse;
import com.bambi.service.common.response.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 모든 컨트롤러의 예외를 공통 응답 포맷으로 변환한다.
 * (인증 전 필터에서 터지는 401/403 은 SecurityConfig 의 EntryPoint/Handler 담당)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 우리가 의도적으로 던진 도메인 예외 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.getCode(), e.getMessage())));
    }

    /** @Valid 검증 실패 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining(", "));
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.getCode(),
                        message.isBlank() ? code.getDefaultMessage() : message)));
    }

    /**
     * 요청 본문을 읽을 수 없음 — 깨진 JSON·잘못된 UTF-8 바이트·타입 불일치 등.
     * 클라이언트 요청 결함이므로 500(INTERNAL_ERROR)이 아니라 400(VALIDATION_ERROR)으로 내린다.
     * (원인 상세는 노출하지 않는다 — SQL·내부 구조 유출 방지)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("요청 본문 파싱 실패: {}", e.getMostSpecificCause().getClass().getSimpleName());
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.getCode(), "요청 본문을 읽을 수 없습니다.")));
    }

    /**
     * 경로/쿼리 파라미터 타입 불일치 — 예: Long 자리에 문자열.
     * 역시 클라이언트 요청 결함이라 400 으로 내린다(기존엔 500 이었음).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        ErrorCode code = ErrorCode.VALIDATION_ERROR;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.getCode(),
                        "요청 파라미터 형식이 올바르지 않습니다: " + e.getName())));
    }

    /** 인가 실패 (@PreAuthorize 등) */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.getCode(), code.getDefaultMessage())));
    }

    /** 예상 못 한 나머지 — 내부 오류로 감싸고 로그 남긴다 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity
                .status(code.getStatus())
                .body(ApiResponse.fail(new ErrorResponse(code.getCode(), code.getDefaultMessage())));
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + ": " + fe.getDefaultMessage();
    }
}
