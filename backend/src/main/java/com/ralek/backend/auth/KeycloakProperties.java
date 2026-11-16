package com.ralek.backend.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ralek.keycloak")
public record KeycloakProperties(String tokenUri, String clientId) {
}
