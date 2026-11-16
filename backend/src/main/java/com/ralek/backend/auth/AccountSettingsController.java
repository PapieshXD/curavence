package com.ralek.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

@RestController
public class AccountSettingsController {
    private final URI console;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    public AccountSettingsController(@Value("${ralek.account-console-url:}") String url) {
        URI parsed=null;
        try {if(!url.isBlank()){var uri=URI.create(url);if(SetOfSchemes.valid(uri))parsed=uri;}}catch(IllegalArgumentException ignored){}
        console=parsed;
    }
    private static class SetOfSchemes {
        static boolean valid(URI uri){return uri.getHost()!=null && uri.getUserInfo()==null && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && (uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1"))));}
    }
    @GetMapping("/api/account/settings")
    public ResponseEntity<?> settings() {
        if(console==null)return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("message","Ustawienia konta nie zostały skonfigurowane."));
        try {
            var response=client.send(java.net.http.HttpRequest.newBuilder(console).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()<200 || response.statusCode()>=400)throw new IllegalStateException();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("accountConsoleUrl",console.toString()));
        } catch(Exception error){return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("message","Ustawienia konta są teraz niedostępne. Spróbuj ponownie."));}
    }
}
