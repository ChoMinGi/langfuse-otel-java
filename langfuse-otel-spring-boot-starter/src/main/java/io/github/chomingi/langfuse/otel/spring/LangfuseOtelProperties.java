package io.github.chomingi.langfuse.otel.spring;

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
}
