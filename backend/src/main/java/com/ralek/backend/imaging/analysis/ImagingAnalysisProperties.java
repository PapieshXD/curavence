package com.ralek.backend.imaging.analysis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ralek.segmentation")
public class ImagingAnalysisProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8090";
    private String serviceToken = "med-node-segmentation-dev";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofHours(2);
    private long maxArtifactBytes = 4_294_967_296L;
    private boolean fast = true;
    private String device = "cpu";
    private boolean runPathologyModels = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    public long getMaxArtifactBytes() { return maxArtifactBytes; }
    public void setMaxArtifactBytes(long maxArtifactBytes) { this.maxArtifactBytes = maxArtifactBytes; }
    public boolean isFast() { return fast; }
    public void setFast(boolean fast) { this.fast = fast; }
    public String getDevice() { return device; }
    public void setDevice(String device) { this.device = device; }
    public boolean isRunPathologyModels() { return runPathologyModels; }
    public void setRunPathologyModels(boolean runPathologyModels) { this.runPathologyModels = runPathologyModels; }
}

