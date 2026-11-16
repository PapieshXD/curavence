package com.ralek.backend.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class SessionController {
    private final KeycloakProperties properties;
    private final JdbcClient jdbc;
    private final RestClient client=RestClient.create();
    public SessionController(KeycloakProperties properties,JdbcClient jdbc) {this.properties=properties;this.jdbc=jdbc;}
    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public ResponseEntity<AuthController.LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        var form=new LinkedMultiValueMap<String,String>();
        form.add("client_id",properties.clientId());form.add("grant_type","refresh_token");form.add("refresh_token",request.refreshToken());
        try {
            Map<?,?> body=client.post().uri(properties.tokenUri()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
            if(body==null || body.get("access_token")==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AuthController.LoginResponse(
                body.get("access_token").toString(),String.valueOf(body.get("refresh_token")),
                ((Number)body.get("expires_in")).longValue(),((Number)body.get("refresh_expires_in")).longValue()));
        } catch(HttpClientErrorException error) {throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesja wygasła");}
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,@RequestBody(required=false) RefreshRequest request) {
        String session=jwt.getClaimAsString("sid");
        if(session==null) session=jwt.getId();
        if(session!=null) jdbc.sql("INSERT INTO revoked_sessions(subject,session_id) VALUES(:subject,:session) ON CONFLICT DO NOTHING")
            .param("subject",jwt.getSubject()).param("session",session).update();
        if(request!=null && request.refreshToken()!=null && !request.refreshToken().isBlank()) {
            var form=new LinkedMultiValueMap<String,String>();
            form.add("client_id",properties.clientId());form.add("refresh_token",request.refreshToken());
            try {client.post().uri(properties.tokenUri().replaceFirst("/token$","/logout"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().toBodilessEntity();}
            catch(org.springframework.web.client.RestClientException ignored) {
                // Local session denylist still blocks access even if Keycloak is temporarily offline.
            }
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
