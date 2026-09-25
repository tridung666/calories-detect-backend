package com.tridung.caloriesdetect.entity;

import com.tridung.caloriesdetect.common.enums.AuthProviderType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "auth_providers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_auth_provider_subject", columnNames = {"provider", "provider_subject"}),
        @UniqueConstraint(name = "uk_auth_user_provider", columnNames = {"user_id", "provider"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthProvider extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuthProviderType provider;

    @Column(name = "provider_subject", length = 255)
    private String providerSubject;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;
}
