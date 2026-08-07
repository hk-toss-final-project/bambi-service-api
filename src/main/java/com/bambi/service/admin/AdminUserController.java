package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminContextSyncResponse;
import com.bambi.service.admin.dto.AdminUserResponse;
import com.bambi.service.admin.dto.AdminUserStatusRequest;
import com.bambi.service.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 관리자 — 사용자 목록 조회.
 *
 * /api/admin/** 는 SecurityConfig 에서 ADMIN 권한으로 막혀 있어, 여기 도달했다는 건
 * 이미 관리자 인증이 끝났다는 뜻이다. 그래서 컨트롤러는 별도 권한 체크 없이 조회만 위임한다.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public ApiResponse<List<AdminUserResponse>> list() {
        return ApiResponse.ok(adminUserService.listUsers());
    }

    /** 사용자 활성/비활성 토글. body {@code {"active": true|false}}. */
    @PatchMapping("/{userId}/status")
    public ApiResponse<AdminUserResponse> setStatus(@PathVariable long userId,
                                                    @RequestBody AdminUserStatusRequest request) {
        return ApiResponse.ok(adminUserService.setActive(userId, request.active()));
    }

    /**
     * agent 컨텍스트 강제 재동기화. 관심사가 agent 에 안 붙은 계정을 관리자가 즉시 복구한다.
     *
     * <p>body 는 없다 — 대상은 경로의 사용자이고 보낼 내용은 서버가 현재 관심사에서 만든다.
     * agent 가 못 받으면 AGENT_UNAVAILABLE(503)로 실패가 그대로 올라간다.
     */
    @PostMapping("/{userId}/context-sync")
    public ApiResponse<AdminContextSyncResponse> resyncContext(@PathVariable long userId) {
        return ApiResponse.ok(adminUserService.resyncAgentContext(userId));
    }
}
