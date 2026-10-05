package com.tridung.caloriesdetect.controller;

import com.tridung.caloriesdetect.common.response.BaseResponse;
import com.tridung.caloriesdetect.dto.response.auth.UserResponse;
import com.tridung.caloriesdetect.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users/me/avatar")
@RequiredArgsConstructor
@Tag(name = "User", description = "User profile APIs")
public class UserAvatarController {
    private final UserService userService;

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaseResponse<UserResponse> uploadAvatar(@RequestPart("file") MultipartFile file) {
        return BaseResponse.success(userService.uploadAvatar(file));
    }

    @DeleteMapping
    public BaseResponse<UserResponse> deleteAvatar() {
        return BaseResponse.success(userService.deleteAvatar());
    }
}
