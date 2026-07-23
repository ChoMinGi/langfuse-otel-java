package io.github.chomingi.langfuse.otel.spring;

import io.github.chomingi.langfuse.otel.ContentCapturePolicy;
import io.github.chomingi.langfuse.otel.ExceptionCapturePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Langfuse OpenTelemetry starter.
 */
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

    /** @return the Langfuse public key */
    public String getPublicKey() { return publicKey; }
    /** @param publicKey Langfuse public key */
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    /** @return the Langfuse secret key */
    public String getSecretKey() { return secretKey; }
    /** @param secretKey Langfuse secret key */
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    /** @return the Langfuse base URL to which the OTLP trace path is appended */
    public String getHost() { return host; }
    /** @param host Langfuse base URL to which the OTLP trace path is appended */
    public void setHost(String host) { this.host = host; }

    /** @return the service name attached to telemetry */
    public String getServiceName() { return serviceName; }
    /** @param serviceName service name attached to telemetry */
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    /** @return the deployment environment attached to traces */
    public String getEnvironment() { return environment; }
    /** @param environment deployment environment attached to traces */
    public void setEnvironment(String environment) { this.environment = environment; }

    /** @return the application release attached to traces */
    public String getRelease() { return release; }
    /** @param release application release attached to traces */
    public void setRelease(String release) { this.release = release; }

    /** @return whether Langfuse auto-configuration is enabled */
    public boolean isEnabled() { return enabled; }
    /** @param enabled whether to enable Langfuse auto-configuration */
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** @return whether development-only insecure HTTP is allowed for loopback endpoints */
    public boolean isAllowInsecureHttpForDevelopment() { return allowInsecureHttpForDevelopment; }
    /**
     * @param allowInsecureHttpForDevelopment whether to allow insecure HTTP for loopback
     * development endpoints
     */
    public void setAllowInsecureHttpForDevelopment(boolean allowInsecureHttpForDevelopment) {
        this.allowInsecureHttpForDevelopment = allowInsecureHttpForDevelopment;
    }

    /** @return the OpenTelemetry ownership mode */
    public OpenTelemetryMode getOtelMode() { return otelMode; }
    /** @param otelMode OpenTelemetry ownership mode */
    public void setOtelMode(OpenTelemetryMode otelMode) { this.otelMode = otelMode; }

    /** @return content-capture settings */
    public Content getContent() { return content; }

    /** @return exception-capture settings */
    public ExceptionDetails getException() { return exception; }

    /** @return request-context capture settings */
    public RequestContext getContext() { return context; }

    /** Defines whether the starter creates or reuses an OpenTelemetry instance. */
    public enum OpenTelemetryMode {
        /** Reuse exactly one application OpenTelemetry bean, otherwise create a standalone SDK. */
        AUTO,
        /** Require and reuse exactly one application OpenTelemetry bean. */
        EXTERNAL,
        /** Always create and own a dedicated Langfuse OpenTelemetry SDK. */
        STANDALONE
    }

    /** Configures prompt and response content capture. */
    public static class Content {

        private boolean captureInput;
        private boolean captureOutput;
        private int maxLength = ContentCapturePolicy.DEFAULT_MAX_LENGTH;

        /** @return whether model input is captured */
        public boolean isCaptureInput() { return captureInput; }
        /** @param captureInput whether to capture model input */
        public void setCaptureInput(boolean captureInput) { this.captureInput = captureInput; }

        /** @return whether model output is captured */
        public boolean isCaptureOutput() { return captureOutput; }
        /** @param captureOutput whether to capture model output */
        public void setCaptureOutput(boolean captureOutput) { this.captureOutput = captureOutput; }

        /** @return the positive post-redaction limit in UTF-16 code units */
        public int getMaxLength() { return maxLength; }
        /** @param maxLength positive post-redaction limit in UTF-16 code units */
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }

    /** Configures exception detail capture. */
    public static class ExceptionDetails {

        private boolean captureMessage;
        private boolean captureStackTrace;
        private int maxLength = ExceptionCapturePolicy.DEFAULT_MAX_LENGTH;

        /** @return whether exception messages are captured */
        public boolean isCaptureMessage() { return captureMessage; }
        /** @param captureMessage whether to capture exception messages */
        public void setCaptureMessage(boolean captureMessage) { this.captureMessage = captureMessage; }

        /** @return whether exception stack traces are captured */
        public boolean isCaptureStackTrace() { return captureStackTrace; }
        /** @param captureStackTrace whether to capture exception stack traces */
        public void setCaptureStackTrace(boolean captureStackTrace) { this.captureStackTrace = captureStackTrace; }

        /** @return the positive post-redaction limit per exception detail in UTF-16 code units */
        public int getMaxLength() { return maxLength; }
        /** @param maxLength positive post-redaction limit per exception detail in UTF-16 code units */
        public void setMaxLength(int maxLength) { this.maxLength = maxLength; }
    }

    /** Configures request-derived Langfuse trace context. */
    public static class RequestContext {

        private boolean captureUserId;
        private boolean captureSessionId;

        /** @return whether {@code Principal#getName()} is captured as the user ID */
        public boolean isCaptureUserId() { return captureUserId; }
        /** @param captureUserId whether to capture {@code Principal#getName()} as the user ID */
        public void setCaptureUserId(boolean captureUserId) { this.captureUserId = captureUserId; }

        /** @return whether the HTTP session ID is captured */
        public boolean isCaptureSessionId() { return captureSessionId; }
        /** @param captureSessionId whether to capture the HTTP session ID */
        public void setCaptureSessionId(boolean captureSessionId) { this.captureSessionId = captureSessionId; }
    }
}
