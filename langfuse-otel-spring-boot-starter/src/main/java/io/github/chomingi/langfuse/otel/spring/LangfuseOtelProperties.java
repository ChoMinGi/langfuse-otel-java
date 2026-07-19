package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.ExceptionCapturePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "langfuse")
public class LangfuseOtelProperties {

    private String publicKey;
    private String secretKey;
    private String host = "https://cloud.langfuse.com";
    private String serviceName = "langfuse-app";
    private String environment;
    private String release;
    private boolean enabled = true;
    private boolean allowInsecureHttpForDevelopment;
    private OpenTelemetryMode otelMode = OpenTelemetryMode.AUTO;
    private final Content content = new Content();
    private final ExceptionDetails exception = new ExceptionDetails();
    private final RequestContext context = new RequestContext();

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getRelease() { return release; }
    public void setRelease(String release) { this.release = release; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAllowInsecureHttpForDevelopment() { return allowInsecureHttpForDevelopment; }
    public void setAllowInsecureHttpForDevelopment(boolean allowInsecureHttpForDevelopment) {
        this.allowInsecureHttpForDevelopment = allowInsecureHttpForDevelopment;
    }

    public OpenTelemetryMode getOtelMode() { return otelMode; }
    public void setOtelMode(OpenTelemetryMode otelMode) { this.otelMode = otelMode; }

    public Content getContent() { return content; }

    public ExceptionDetails getException() { return exception; }

    public RequestContext getContext() { return context; }

    public enum OpenTelemetryMode {
        /** Reuse exactly one application OpenTelemetry bean, otherwise create a standalone SDK. */
        AUTO,
        /** Require and reuse exactly one application OpenTelemetry bean. */
        EXTERNAL,
        /** Always create and own a dedicated Langfuse OpenTelemetry SDK. */
        STANDALONE
    }

    public static class Content {

        private boolean captureInput;
        private boolean captureOutput;
        private int maxLength = ContentCapturePolicy.DEFAULT_MAX_LENGTH;

        public boolean isCaptureInput() { return captureInput; }
        public void setCaptureInput(boolean captureInput) { this.captureInput = captureInput; }

        public boolean isCaptureOutput() { return captureOutput; }
        public void setCaptureOutput(boolean captureOutput) { this.captureOutput = captureOutput; }

        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }

    public static class ExceptionDetails {

        private boolean captureMessage;
        private boolean captureStackTrace;
        private int maxLength = ExceptionCapturePolicy.DEFAULT_MAX_LENGTH;

        public boolean isCaptureMessage() { return captureMessage; }
        public void setCaptureMessage(boolean captureMessage) { this.captureMessage = captureMessage; }

        public boolean isCaptureStackTrace() { return captureStackTrace; }
        public void setCaptureStackTrace(boolean captureStackTrace) { this.captureStackTrace = captureStackTrace; }

        public int getMaxLength() { return maxLength; }
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }

    public static class RequestContext {

        private boolean captureUserId;
        private boolean captureSessionId;

        public boolean isCaptureUserId() { return captureUserId; }
        public void setCaptureUserId(boolean captureUserId) { this.captureUserId = captureUserId; }

        public boolean isCaptureSessionId() { return captureSessionId; }
        public void setCaptureSessionId(boolean captureSessionId) { this.captureSessionId = captureSessionId; }
    }
}
