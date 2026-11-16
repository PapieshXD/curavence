package com.ralek.backend.document;

import com.ralek.backend.document.DocumentDtos.DocumentUploadResponse;
import com.ralek.backend.document.PdfDocumentProcessor.PreparedPdf;
import com.ralek.backend.workspace.WorkspaceService;
import com.ralek.backend.workspace.WorkspaceService.DerivedAsset;
import com.ralek.backend.workspace.WorkspaceService.PreparedUpload;
import com.ralek.backend.workspace.WorkspaceService.StoredStudy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Locale;
import java.util.Set;

@Service
public class DocumentService {

    private static final String PDF_MIME_TYPE = "application/pdf";
    private static final String TEXT_MIME_TYPE = "text/plain; charset=UTF-8";

    private final com.ralek.backend.workspace.DocumentLibraryService library;
    private final PdfDocumentProcessor processor;
    private final EcgFileProcessor ecgProcessor;
    private final ClinicalResultParser clinicalResultParser;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public DocumentService(PdfDocumentProcessor processor, ClinicalResultParser clinicalResultParser,
                           WorkspaceService workspaceService, ObjectMapper objectMapper, EcgFileProcessor ecgProcessor, com.ralek.backend.workspace.DocumentLibraryService library) {
        this.library=library;
        this.processor = processor;
        this.ecgProcessor = ecgProcessor;
        this.clinicalResultParser = clinicalResultParser;
        this.workspaceService = workspaceService;
        this.objectMapper = objectMapper;
    }

    public DocumentUploadResponse upload(Jwt jwt, String requestedView, MultipartFile file,
                                         String title, UUID patientId, Instant performedAt) {
        return upload(jwt, requestedView, file, title, patientId, performedAt, null);
    }

    public DocumentUploadResponse upload(Jwt jwt, String requestedView, MultipartFile file,
                                         String title, UUID patientId, Instant performedAt, String requestedType) {
        String typeCode = normalizeDocumentType(requestedType);
        if (typeCode.equals("EKG") && file != null
                && !(file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                && !PDF_MIME_TYPE.equalsIgnoreCase(file.getContentType())) {
            var ecg = ecgProcessor.process(file);
            String filename = file.getOriginalFilename();
            String effectiveTitle = title == null || title.isBlank() ? titleFrom(filename) : title.trim();
            String metadata = json(Map.of("documentProcessing", Map.of(
                    "format", ecg.format(), "processingStatus", "ORIGINAL_STORED",
                    "classifierStatus", "NOT_APPLICABLE")));
            var upload = new PreparedUpload(filename, ecg.mimeType(), ecg.bytes().length,
                    () -> new ByteArrayInputStream(ecg.bytes()), metadata, List.of());
            var stored = workspaceService.uploadPreparedStudy(jwt, requestedView, upload,
                    effectiveTitle, typeCode, patientId, performedAt);
            return new DocumentUploadResponse(stored.studyId(), "READY", "ORIGINAL_STORED", 0, 0, false, library.duplicates(stored.studyId()));
        }
        PreparedPdf pdf = processor.process(file);
        String filename = file.getOriginalFilename();
        String effectiveTitle = title == null || title.isBlank() ? titleFrom(filename) : title.trim();
        byte[] text = pdf.text().getBytes(StandardCharsets.UTF_8);
        List<DerivedAsset> derivedAssets = text.length == 0
                ? List.of()
                : List.of(new DerivedAsset(textFilename(filename), TEXT_MIME_TYPE,
                        "EXTRACTED_TEXT", "txt", text));

        String classifierStatus = pdf.status() == PdfDocumentProcessor.ProcessingStatus.TEXT_EXTRACTED
                ? "PENDING" : "WAITING_FOR_OCR";
        Optional<ClinicalResultDtos.ClinicalResultSet> clinicalResult =
                Set.of("MEDICAL_DOCUMENT", "LABORATORY", "OTHER").contains(typeCode)
                        ? clinicalResultParser.parse(pdf.text(), effectiveTitle) : Optional.empty();
        if (clinicalResult.isPresent()) classifierStatus = "CLASSIFIED_AS_RESULT";
        Map<String, Object> metadataValue = new LinkedHashMap<>();
        metadataValue.put("documentProcessing", Map.of(
                "format", "PDF",
                "processingStatus", pdf.status().name(),
                "pages", pdf.pages(),
                "extractedCharacters", pdf.text().length(),
                "textTruncated", pdf.textTruncated(),
                "classifierStatus", classifierStatus
        ));
        clinicalResult.ifPresent(result -> metadataValue.put("clinicalResult", result));
        String metadata = json(metadataValue);
        byte[] original = pdf.bytes();
        PreparedUpload upload = new PreparedUpload(filename, PDF_MIME_TYPE, original.length,
                () -> new ByteArrayInputStream(original), metadata, derivedAssets);
        StoredStudy stored = workspaceService.uploadPreparedStudy(jwt, requestedView, upload,
                effectiveTitle, typeCode, patientId, performedAt);

        library.index(stored.studyId(),pdf.text());
        return new DocumentUploadResponse(stored.studyId(), "READY", pdf.status().name(),
                pdf.pages(), pdf.text().length(), pdf.textTruncated(), library.duplicates(stored.studyId()));
    }

    public Optional<ClinicalResultDtos.ClinicalResultSet> clinicalResult(
            Jwt jwt, String requestedView, UUID documentId) {
        WorkspaceService.DocumentContent document =
                workspaceService.documentContent(jwt, requestedView, documentId);
        return clinicalResultParser.parse(document.text(), document.title());
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Nie udało się przygotować metadanych dokumentu", exception);
        }
    }

    static String normalizeDocumentType(String requestedType) {
        String type = requestedType == null || requestedType.isBlank() ? "MEDICAL_DOCUMENT" : requestedType.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MEDICAL_DOCUMENT", "LABORATORY", "ULTRASOUND", "EKG", "EEG", "MRI", "CT", "XRAY", "OTHER").contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nieobsługiwany rodzaj dokumentu.");
        }
        return type;
    }

    private static String titleFrom(String filename) {
        if (filename == null || filename.isBlank()) return "Dokument medyczny";
        String normalized = filename.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").trim();
        String title = name.toLowerCase().endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        return title.isBlank() ? "Dokument medyczny" : title.substring(0, Math.min(title.length(), 255));
    }

    private static String textFilename(String filename) {
        return titleFrom(filename) + ".txt";
    }
}
