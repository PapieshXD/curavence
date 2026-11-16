package com.ralek.backend.auth;

import com.ralek.backend.audit.AuditService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountSettingsTest {
    @Test void consoleMustBeConfiguredAndReachable() throws Exception {
        assertEquals(503,new AccountSettingsController("").settings().getStatusCode().value());
        assertEquals(503,new AccountSettingsController("javascript:alert(1)").settings().getStatusCode().value());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/account",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/account";
        try{assertEquals(200,new AccountSettingsController(url).settings().getStatusCode().value());}finally{server.stop(0);}
        assertEquals(503,new AccountSettingsController(url).settings().getStatusCode().value());
    }
    @Test void loginForwardsOptionalOtpToTheExistingIdentityProvider() throws Exception {
        var body=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/token",exchange->{body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] json="{\"access_token\":\"fixture\",\"refresh_token\":\"fixture-refresh\",\"expires_in\":60,\"refresh_expires_in\":120}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,json.length);exchange.getResponseBody().write(json);exchange.close();});server.start();
        try {
            var service=new AuthService(new KeycloakProperties("http://127.0.0.1:"+server.getAddress().getPort()+"/token","test-client"),mock(AuditService.class));
            assertEquals("fixture",service.login(new AuthController.LoginRequest("test@example.test","test-password","123456"),"127.0.0.1").accessToken());
            assertTrue(body.get().contains("totp=123456"));
            service.login(new AuthController.LoginRequest("test@example.test","test-password"),"127.0.0.1");assertFalse(body.get().contains("totp="));
        }finally{server.stop(0);}
    }
}
