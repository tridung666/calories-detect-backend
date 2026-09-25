package com.tridung.caloriesdetect.repository;

import com.tridung.caloriesdetect.common.enums.AuthProviderType;
import com.tridung.caloriesdetect.entity.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthProviderRepository extends JpaRepository<AuthProvider, Long> {
    Optional<AuthProvider> findByUserIdAndProvider(Long userId, AuthProviderType provider);

    Optional<AuthProvider> findByProviderAndProviderSubject(AuthProviderType provider, String providerSubject);
}
