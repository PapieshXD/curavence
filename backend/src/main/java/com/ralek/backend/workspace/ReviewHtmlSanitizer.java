package com.ralek.backend.workspace;

import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A narrow HTML vocabulary for clinical notes. Images are small, inline raster files only. */
final class ReviewHtmlSanitizer {
    private static final int MAX_HTML_LENGTH = 2_000_000;
    private static final int MAX_IMAGE_LENGTH = 600_000;
    private static final Pattern IMAGE = Pattern.compile("^data:image/(jpeg|png);base64,([A-Za-z0-9+/]+={0,2})$");
    private static final Safelist ALLOWED = new Safelist()
            .addTags("p", "br", "strong", "em", "u", "ol", "ul", "li", "h2", "blockquote", "img")
            .addAttributes("img", "src", "alt");

    private ReviewHtmlSanitizer() { }

    static String sanitize(String html) {
        if (html == null || html.isBlank()) return null;
        if (html.length() > MAX_HTML_LENGTH) badRequest("Opis ze zdjęciami jest zbyt duży");
        Document dirty = Jsoup.parseBodyFragment(html);
        Document clean = new Cleaner(ALLOWED).clean(dirty);
        if (clean.select("img").size() > 3) badRequest("W jednym polu można umieścić maksymalnie 3 zdjęcia");
        for (Element image : clean.select("img")) validateImage(image.attr("src"));
        return clean.body().html();
    }

    private static void validateImage(String source) {
        if (source.length() > MAX_IMAGE_LENGTH) badRequest("Zdjęcie w opisie jest zbyt duże");
        Matcher matcher = IMAGE.matcher(source);
        if (!matcher.matches()) badRequest("Do opisu można wstawiać tylko zdjęcia JPG i PNG");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(matcher.group(2)); }
        catch (IllegalArgumentException exception) { badRequest("Nieprawidłowe dane zdjęcia"); return; }
        boolean jpeg = matcher.group(1).equals("jpeg") && bytes.length >= 3
                && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
        boolean png = matcher.group(1).equals("png") && bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
        if (!jpeg && !png) badRequest("Zawartość zdjęcia nie zgadza się z jego typem");
    }

    private static void badRequest(String message) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
