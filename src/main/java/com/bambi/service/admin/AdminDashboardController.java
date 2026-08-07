package com.bambi.service.admin;

import com.bambi.service.admin.dto.AdminDashboardResponse;
import com.bambi.service.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 — 운영 대시보드.
 *
 * 첫 화면이 지표 여러 개를 한꺼번에 필요로 해서, 화면이 API 를 네 번 부르는 대신
 * 한 번에 묶어 내린다. /api/admin/** 는 SecurityConfig 에서 ADMIN 으로 막혀 있다.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    private final AdminDashboardService adminDashboardService;

    public AdminDashboardController(AdminDashboardService adminDashboardService) {
        this.adminDashboardService = adminDashboardService;
    }

    @GetMapping
    public ApiResponse<AdminDashboardResponse> overview() {
        return ApiResponse.ok(adminDashboardService.getOverview());
    }
}
