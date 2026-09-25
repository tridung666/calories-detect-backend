package com.tridung.caloriesdetect.security;

import com.tridung.caloriesdetect.repository.UserRepository;
import com.tridung.caloriesdetect.repository.AuthProviderRepository;
import com.tridung.caloriesdetect.common.enums.AuthProviderType;
import com.tridung.caloriesdetect.entity.AuthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmailIgnoreCase(email)
                .map(user -> new CustomUserDetails(user, authProviderRepository
                        .findByUserIdAndProvider(user.getId(), AuthProviderType.LOCAL)
                        .map(AuthProvider::getPasswordHash).orElse(null)))
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }
}
