package com.ralek.backend.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import static org.assertj.core.api.Assertions.*;

class EcgFileProcessorTest {
    private final EcgFileProcessor processor = new EcgFileProcessor(new DocumentProperties(1024, 10, 100, 10));

    @ParameterizedTest
    @ValueSource(strings = {"txt", "csv", "tsv", "xml", "json", "ecg", "scp", "edf", "bdf", "gdf", "hea", "dat", "atr", "mat", "dcm", "dicom", "zip", "tif", "tiff", "webp"})
    void preservesOpaqueExportsWithoutTrustingMimeOrClaimingDecoding(String extension) {
        byte[] content = {1, 2, 3, 4};
        var result = processor.process(new MockMultipartFile("file", "record." + extension.toUpperCase(), "text/html", content));
        assertThat(result.bytes()).isEqualTo(content);
        assertThat(result.mimeType()).isEqualTo("application/octet-stream");
    }

    @Test
    void detectsImageMimeFromContent() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        var result = processor.process(new MockMultipartFile("file", "record.PNG", "application/octet-stream", out.toByteArray()));
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(result.bytes()).isEqualTo(out.toByteArray());
    }

    @Test
    void rejectsInvalidUploads() {
        assertThatThrownBy(() -> processor.process(null)).isInstanceOf(ResponseStatusException.class);
        for (var file : new MockMultipartFile[]{
                new MockMultipartFile("file", "record.csv", "text/plain", new byte[0]),
                new MockMultipartFile("file", "record.csv", "text/plain", new byte[1025]),
                new MockMultipartFile("file", "record.exe", "image/png", new byte[]{1}),
                new MockMultipartFile("file", "record.png", "image/png", new byte[]{1})}) {
            assertThatThrownBy(() -> processor.process(file)).isInstanceOf(ResponseStatusException.class);
        }
    }
}
