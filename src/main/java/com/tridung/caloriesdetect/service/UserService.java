package com.tridung.caloriesdetect.service;

import com.tridung.caloriesdetect.common.response.PageResponse;
import com.tridung.caloriesdetect.dto.request.admin.AdminUserRequest;
import com.tridung.caloriesdetect.dto.response.UserResponse;

public interface UserService {
    UserResponse getUserById(Long id);
    PageResponse<UserResponse> getAllUsers(int  pageNo, int pageSize);
    UserResponse createOneUser(AdminUserRequest user);
}
