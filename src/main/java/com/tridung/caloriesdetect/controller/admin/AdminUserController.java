package com.tridung.caloriesdetect.controller.admin;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import com.tridung.caloriesdetect.common.response.PageResponse;
import com.tridung.caloriesdetect.dto.request.admin.AdminUserRequest;
import com.tridung.caloriesdetect.dto.response.UserResponse;
import com.tridung.caloriesdetect.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("api/admin")
public class AdminUserController {
    private final UserService userService;

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/users")
    public BaseResponse<PageResponse<UserResponse>> getALlUsers(@RequestParam int  pageNo,
                                                                @RequestParam int pageSize) {
        return BaseResponse.success(userService.getAllUsers(pageNo, pageSize));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/users")
    public BaseResponse<UserResponse> createOneUser(@Valid @RequestBody AdminUserRequest adminUserRequest) {
        return BaseResponse.success(userService.createOneUser(adminUserRequest));
    }
}
