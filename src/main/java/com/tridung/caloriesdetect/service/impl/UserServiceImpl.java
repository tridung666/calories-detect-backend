package com.tridung.caloriesdetect.service.impl;

import com.tridung.caloriesdetect.common.response.PageResponse;
import com.tridung.caloriesdetect.dto.request.admin.AdminUserRequest;
import com.tridung.caloriesdetect.dto.response.auth.UserResponse;
import com.tridung.caloriesdetect.entity.User;
import com.tridung.caloriesdetect.entity.AuthProvider;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import org.springframework.transaction.annotation.Transactional;
import com.tridung.caloriesdetect.exception.AppException;
import com.tridung.caloriesdetect.exception.ErrorCode;
import com.tridung.caloriesdetect.mapper.UserMapper;
import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;


    @Override
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        return userMapper.toUserResponse(user);
    }

    @Override
    public PageResponse<UserResponse> getAllUsers(int pageNo, int pageSize) {
        Pageable pageable = PageRequest.of(pageNo, pageSize);
        Page<User> users = userRepository.findAll(pageable);
        return PageResponse.from(users, userMapper::toUserResponse);
    }

    @Override
    @Transactional
    public UserResponse createOneUser(AdminUserRequest rq) {
        if(userRepository.existsByEmailIgnoreCase(rq.email())) {
            throw new AppException(ErrorCode.USER_EXISTED);
        }

        String encodedPassword = passwordEncoder.encode(rq.password());

        User user = userMapper.toEntity(rq);

        User savedUser = userRepository.save(user);
        authProviderRepository.save(AuthProvider.builder().user(savedUser)
                .provider(AuthProviderType.LOCAL).passwordHash(encodedPassword).build());

        return userMapper.toUserResponse(savedUser);
    }
}
