package com.tridung.caloriesdetect.service;

import org.springframework.web.multipart.MultipartFile;
import com.tridung.caloriesdetect.common.response.PageResponse;
import com.tridung.caloriesdetect.dto.request.admin.AdminUserRequest;
import com.tridung.caloriesdetect.dto.response.auth.UserResponse;

public interface UserService {
    UserResponse uploadAvatar(MultipartFile file);
    UserResponse deleteAvatar();

    UserResponse getUserById(Long id);
    PageResponse<UserResponse> getAllUsers(int  pageNo, int pageSize);
    UserResponse createOneUser(AdminUserRequest user);
}
