// Importing the loader inside an application-owned worker makes Angular bundle
// Cornerstone's decoder entry (and its WASM codecs) instead of leaving the
// package-internal worker URL unresolved at runtime.
import '@cornerstonejs/dicom-image-loader';
