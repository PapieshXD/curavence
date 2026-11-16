package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.domain.DicomSeriesMetadata;
import com.ralek.backend.imaging.domain.DicomStoreResult;
import com.ralek.backend.imaging.domain.DicomStudyMetadata;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface DicomStoragePort {

    StorageProvider provider();

    DicomStoreResult store(Path dicomDirectory);

    DicomStudyMetadata getStudy(String studyInstanceUid);

    List<DicomSeriesMetadata> getSeries(String studyInstanceUid);

    DicomWebResponse execute(DicomWebRequest request, String enforcedStudyInstanceUid);

    record DicomWebRequest(String method, String path, String queryString,
                           Map<String, List<String>> headers) { }

    record DicomWebResponse(int status, Map<String, List<String>> headers,
                            InputStream body) implements AutoCloseable {
        @Override
        public void close() throws java.io.IOException {
            if (body != null) body.close();
        }
    }
}
