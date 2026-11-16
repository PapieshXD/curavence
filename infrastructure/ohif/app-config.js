// Compatibility shim for a dependency bundled by the stable OHIF 3.12 image.
var __filename = '/usr/share/nginx/html/index.html';
var __dirname = '/usr/share/nginx/html';

window.config = {
  routerBasename: '/',
  showStudyList: false,
  dangerouslyUseDynamicConfig: {
    enabled: true,
    regex: /^http:\/\/127\.0\.0\.1:3000\/api\/v1\/viewer\/[0-9a-f-]+\/config\?simplified=(?:true|false)$/,
  },
  extensions: [],
  modes: [],
  dataSources: [
    {
      namespace: '@ohif/extension-default.dataSourcesModule.dicomweb',
      sourceName: 'dicomweb',
      configuration: {
        friendlyName: 'Curavence Imaging',
        name: 'Curavence',
        wadoUriRoot: 'http://127.0.0.1:8080/api/v1/dicomweb',
        qidoRoot: 'http://127.0.0.1:8080/api/v1/dicomweb',
        wadoRoot: 'http://127.0.0.1:8080/api/v1/dicomweb',
        qidoSupportsIncludeField: true,
        supportsReject: false,
        imageRendering: 'wadors',
        thumbnailRendering: 'wadors',
        enableStudyLazyLoad: true,
        supportsFuzzyMatching: false,
        supportsWildcard: true,
      },
    },
  ],
  defaultDataSourceName: 'dicomweb',
  showWarningMessageForCrossOrigin: false,
  showCPUFallbackMessage: true,
  showLoadingIndicator: true,
};

// OHIF 3.12 builds the longitudinal side panel before the dynamic mode
// override is resolved. Keep the patient-only simplified session deterministic
// by removing the already-mounted panel and its resize handle. The advanced
// session has simplified=false, so it retains the stock OHIF study browser.
(function applyMedNodeSimplifiedViewer() {
  var configUrl = new URLSearchParams(window.location.search).get('configUrl') || '';
  var simplified = /[?&]simplified=true(?:&|$)/.test(configUrl);
  document.documentElement.setAttribute('data-mednode-simplified-viewer', String(simplified));
  if (!simplified) return;
  var style = document.createElement('style');
  style.setAttribute('data-mednode-simplified-viewer', '');
  style.textContent = [
    '#viewerLayoutResizableLeftPanel,',
    '#viewerLayoutResizableLeftPanel + [role="separator"] {',
    '  display: none !important;',
    '}',
  ].join('\n');
  document.head.appendChild(style);
})();
