package com.ralek.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return authService.login(request, servletRequest.getRemoteAddr());
    }

    public record LoginRequest(@NotBlank @Email String username, @NotBlank String password,
        @jakarta.validation.constraints.Pattern(regexp="[0-9]{6,8}") String otp) {
        public LoginRequest(String username,String password){this(username,password,null);}
    }

    public record LoginResponse(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn) {
    }
}

@Service
@EnableConfigurationProperties(KeycloakProperties.class)
class AuthService {

    private final RestClient restClient;
    private final KeycloakProperties properties;
    private final com.ralek.backend.audit.AuditService auditService;

    AuthService(KeycloakProperties properties, com.ralek.backend.audit.AuditService auditService) {
        this.restClient = RestClient.create();
        this.properties = properties;
        this.auditService = auditService;
    }

    AuthController.LoginResponse login(AuthController.LoginRequest request, String remoteAddress) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.clientId());
        form.add("username", request.username());
        form.add("password", request.password());
        if(request.otp()!=null && !request.otp().isBlank())form.add("totp",request.otp());

        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri(properties.tokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toEntity(Map.class);
            Map<?, ?> body = response.getBody();
            if (body == null || body.get("access_token") == null) {
                throw new ResponseStatusException(UNAUTHORIZED, "Keycloak nie zwrócił tokenu dostępu");
            }
            auditService.recordLogin(request.username(), true, null, remoteAddress);
            return new AuthController.LoginResponse(
                    body.get("access_token").toString(),
                    value(body, "refresh_token"),
                    number(body, "expires_in"),
                    number(body, "refresh_expires_in")
            );
        } catch (HttpClientErrorException exception) {
            auditService.recordLogin(request.username(), false, "Nieprawidłowy login, hasło lub kod uwierzytelniający", remoteAddress);
            throw new ResponseStatusException(UNAUTHORIZED, "Nieprawidłowy login, hasło lub kod uwierzytelniający");
        }
    }

    private static String value(Map<?, ?> body, String key) {
        return body.get(key) == null ? "" : body.get(key).toString();
    }

    private static long number(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }
}
