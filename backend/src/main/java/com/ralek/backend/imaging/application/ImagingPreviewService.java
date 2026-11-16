package com.ralek.backend.imaging.application;

import com.ralek.backend.imaging.api.ImagingDtos.ImagingSeriesDto;
import com.ralek.backend.imaging.api.ImagingDtos.ImagingStudyDetails;
import com.ralek.backend.imaging.infrastructure.ImagingPreviewWorkerClient;
import com.ralek.backend.imaging.infrastructure.ImagingPreviewWorkerClient.Legend;
import com.ralek.backend.imaging.infrastructure.ImagingPreviewWorkerClient.Manifest;
import com.ralek.backend.imaging.infrastructure.ImagingPreviewWorkerClient.Mask;
import com.ralek.backend.imaging.infrastructure.ImagingPreviewWorkerClient.Request;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ImagingPreviewService {
    private final ImagingStudyService studies;
    private final ImagingPreviewWorkerClient worker;
    private final JdbcClient jdbc;

    public ImagingPreviewService(ImagingStudyService studies, ImagingPreviewWorkerClient worker, JdbcClient jdbc) {
        this.studies = studies;
        this.worker = worker;
        this.jdbc = jdbc;
    }

    public Overview overview(Jwt jwt, String role, UUID studyId, UUID requestedSeries) {
        ImagingStudyDetails details = accessible(jwt, role, studyId);
        ImagingSeriesDto selected = choose(details, requestedSeries);
        Manifest manifest = prepare(details, selected, true);
        String root = root(studyId);
        return new Overview(studyId, selected.id(), root + "/preview", details.series().stream().map(series -> {
            if (!series.id().equals(selected.id())) {
                return new Series(series.id(), series.seriesInstanceUid(), description(series), series.modality(),
                        series.imageCount(), List.of(), null, null, 0, 0, 0, 0,
                        "NOT_ANALYZED", "Wybierz serię, aby załadować jej obrazy i dostępne oznaczenia.", List.of(), List.of());
            }
            String base = root + "/series/" + series.id();
            return new Series(series.id(), series.seriesInstanceUid(), description(series), series.modality(),
                    manifest.frameCount(), manifest.frames().stream().map(frame -> new Frame(frame.id(), frame.index(),
                    frame.sopInstanceUid(), frame.frameNumber(), frame.rows(), frame.columns(),
                    base + "/frames/" + frame.index() + "/image",
                    manifest.hasOverlay() && frame.hasOverlay() ? base + "/frames/" + frame.index() + "/overlay" : null, frame.alignmentKey())).toList(),
                    base + "/contact-sheet", manifest.hasOverlay() ? base + "/contact-sheet?overlay=true" : null,
                    manifest.contactSheetColumns(), manifest.contactSheetRows(), manifest.cellWidth(), manifest.cellHeight(),
                    manifest.overlayStatus(), manifest.overlayMessage(), manifest.legend(), manifest.warnings());
        }).toList());
    }

    public byte[] preview(Jwt jwt, String role, UUID studyId) {
        ImagingStudyDetails details = accessible(jwt, role, studyId);
        Manifest manifest = prepare(details, choose(details, null), false);
        return worker.image(manifest.cacheKey(), "preview.png");
    }

    public byte[] image(Jwt jwt, String role, UUID studyId, UUID seriesId, Integer index, boolean overlay) {
        ImagingStudyDetails details = accessible(jwt, role, studyId);
        ImagingSeriesDto series = choose(details, seriesId);
        Manifest manifest = prepare(details, series, true);
        if (overlay && !manifest.hasOverlay()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Brak dopasowanej maski dla tej serii");
        }
        if (index != null && (index < 0 || index >= manifest.frameCount())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nie znaleziono klatki w tej serii");
        }
        String filename = index == null ? (overlay ? "sheet-overlay.png" : "sheet.png")
                : (overlay ? "overlay-" : "image-") + index + ".png";
        return worker.image(manifest.cacheKey(), filename);
    }

    private ImagingStudyDetails accessible(Jwt jwt, String role, UUID studyId) {
        // All PNGs, including previously cached thumbnails, re-check current grants through details().
        ImagingStudyDetails details = studies.details(jwt, role, studyId);
        if (!"AVAILABLE".equals(details.study().status()) || details.study().studyInstanceUid() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Obrazy będą dostępne po zakończeniu importu");
        }
        if (!"ORTHANC".equalsIgnoreCase(details.study().storageProvider())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Ten magazyn obrazów wymaga przeglądarki zaawansowanej");
        }
        return details;
    }

    private ImagingSeriesDto choose(ImagingStudyDetails details, UUID requested) {
        if (requested != null) {
            return details.series().stream().filter(series -> series.id().equals(requested)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seria nie należy do tego badania"));
        }
        return details.series().stream().filter(series -> series.imageCount() > 0)
                .max(Comparator.comparingInt((ImagingSeriesDto series) -> isDiagnostic(series) ? 1 : 0)
                        .thenComparingInt(ImagingSeriesDto::imageCount))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Badanie nie zawiera dostępnych obrazów"));
    }

    private Manifest prepare(ImagingStudyDetails details, ImagingSeriesDto series, boolean includeMasks) {
        String analysis = includeMasks ? jdbc.sql("""
                        SELECT id::text FROM imaging_analyses WHERE study_id = :study AND status IN ('SUCCEEDED', 'DEGRADED')
                        ORDER BY completed_at DESC NULLS LAST, created_at DESC LIMIT 1
                        """).param("study", details.study().id()).query(String.class).optional().orElse(null) : null;
        List<Mask> masks = analysis == null ? List.of() : jdbc.sql("""
                        SELECT artifact.id::text, artifact.kind
                        FROM imaging_analysis_artifacts artifact
                        JOIN storage_objects object ON object.id = artifact.storage_object_id
                        WHERE artifact.analysis_id = :analysis AND artifact.series_id = :series
                          AND artifact.kind IN ('ANATOMY_MASKS', 'CANDIDATE_MASK')
                          AND object.storage_status = 'STORED'
                        ORDER BY artifact.kind, artifact.id
                        """).param("analysis", UUID.fromString(analysis)).param("series", series.id())
                .query((rs, rowNum) -> new Mask(rs.getString("id"), rs.getString("kind"))).list();
        return worker.prepare(new Request(details.study().studyInstanceUid(), series.seriesInstanceUid(), analysis, masks));
    }

    private static boolean isDiagnostic(ImagingSeriesDto series) {
        String text = (description(series) + " " + series.sequenceName()).toLowerCase(Locale.ROOT);
        return !text.matches(".*(localizer|scout|topogram|survey|locator).*");
    }

    private static String description(ImagingSeriesDto series) {
        return series.description() == null || series.description().isBlank() ? "Seria " + (series.seriesNumber() == null ? "obrazów" : series.seriesNumber()) : series.description();
    }

    private static String root(UUID studyId) { return "/api/v1/imaging-studies/" + studyId; }

    public record Overview(UUID studyId, UUID selectedSeriesId, String previewUrl, List<Series> series) { }
    public record Frame(String id, int index, String sopInstanceUid, int frameNumber, int rows, int columns,
                         String imageUrl, String overlayUrl, String alignmentKey) { }
    public record Series(UUID id, String seriesInstanceUid, String description, String modality, int frameCount,
                         List<Frame> frames, String contactSheetUrl, String overlayContactSheetUrl,
                         int contactSheetColumns, int contactSheetRows, int cellWidth, int cellHeight,
                         String overlayStatus, String overlayMessage, List<Legend> legend, List<String> warnings) { }
}
