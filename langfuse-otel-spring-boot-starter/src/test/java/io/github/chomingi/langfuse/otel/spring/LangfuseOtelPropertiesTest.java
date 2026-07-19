package io.github.chomingi.langfuse.otel.spring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangfuseOtelPropertiesTest {

    @Test
    void defaultValues() {
        LangfuseOtelProperties props = new LangfuseOtelProperties();

        assertThat(props.getHost()).isEqualTo("https://cloud.langfuse.com");
        assertThat(props.getServiceName()).isEqualTo("langfuse-app");
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isAllowInsecureHttpForDevelopment()).isFalse();
        assertThat(props.getOtelMode()).isEqualTo(LangfuseOtelProperties.OpenTelemetryMode.AUTO);
        assertThat(props.getPublicKey()).isNull();
        assertThat(props.getSecretKey()).isNull();
        assertThat(props.getEnvironment()).isNull();
        assertThat(props.getRelease()).isNull();
        assertThat(props.getContent().isCaptureInput()).isFalse();
        assertThat(props.getContent().isCaptureOutput()).isFalse();
        assertThat(props.getContent().getMaxLength()).isEqualTo(8_192);
        assertThat(props.getException().isCaptureMessage()).isFalse();
        assertThat(props.getException().isCaptureStackTrace()).isFalse();
        assertThat(props.getException().getMaxLength()).isEqualTo(8_192);
        assertThat(props.getContext().isCaptureUserId()).isFalse();
        assertThat(props.getContext().isCaptureSessionId()).isFalse();
    }

    @Test
    void settersAndGetters() {
        LangfuseOtelProperties props = new LangfuseOtelProperties();

        props.setPublicKey("pk-lf-test");
        props.setSecretKey("sk-lf-test");
        props.setHost("https://self-hosted.example.com");
        props.setServiceName("my-service");
        props.setEnvironment("production");
        props.setRelease("v1.2.3");
        props.setEnabled(false);
        props.setAllowInsecureHttpForDevelopment(true);
        props.setOtelMode(LangfuseOtelProperties.OpenTelemetryMode.STANDALONE);
        props.getContent().setCaptureInput(true);
        props.getContent().setCaptureOutput(true);
        props.getContent().setMaxLength(1_024);
        props.getException().setCaptureMessage(true);
        props.getException().setCaptureStackTrace(true);
        props.getException().setMaxLength(512);
        props.getContext().setCaptureUserId(true);
        props.getContext().setCaptureSessionId(true);

        assertThat(props.getPublicKey()).isEqualTo("pk-lf-test");
        assertThat(props.getSecretKey()).isEqualTo("sk-lf-test");
        assertThat(props.getHost()).isEqualTo("https://self-hosted.example.com");
        assertThat(props.getServiceName()).isEqualTo("my-service");
        assertThat(props.getEnvironment()).isEqualTo("production");
        assertThat(props.getRelease()).isEqualTo("v1.2.3");
        assertThat(props.isEnabled()).isFalse();
        assertThat(props.isAllowInsecureHttpForDevelopment()).isTrue();
        assertThat(props.getOtelMode()).isEqualTo(LangfuseOtelProperties.OpenTelemetryMode.STANDALONE);
        assertThat(props.getContent().isCaptureInput()).isTrue();
        assertThat(props.getContent().isCaptureOutput()).isTrue();
        assertThat(props.getContent().getMaxLength()).isEqualTo(1_024);
        assertThat(props.getException().isCaptureMessage()).isTrue();
        assertThat(props.getException().isCaptureStackTrace()).isTrue();
        assertThat(props.getException().getMaxLength()).isEqualTo(512);
        assertThat(props.getContext().isCaptureUserId()).isTrue();
        assertThat(props.getContext().isCaptureSessionId()).isTrue();
    }

    @Test
    void nullOptionalFieldsAreAccepted() {
        LangfuseOtelProperties props = new LangfuseOtelProperties();

        props.setEnvironment(null);
        props.setRelease(null);

        assertThat(props.getEnvironment()).isNull();
        assertThat(props.getRelease()).isNull();
    }
}
