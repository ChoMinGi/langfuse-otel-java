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
        assertThat(props.getPublicKey()).isNull();
        assertThat(props.getSecretKey()).isNull();
        assertThat(props.getEnvironment()).isNull();
        assertThat(props.getRelease()).isNull();
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

        assertThat(props.getPublicKey()).isEqualTo("pk-lf-test");
        assertThat(props.getSecretKey()).isEqualTo("sk-lf-test");
        assertThat(props.getHost()).isEqualTo("https://self-hosted.example.com");
        assertThat(props.getServiceName()).isEqualTo("my-service");
        assertThat(props.getEnvironment()).isEqualTo("production");
        assertThat(props.getRelease()).isEqualTo("v1.2.3");
        assertThat(props.isEnabled()).isFalse();
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
