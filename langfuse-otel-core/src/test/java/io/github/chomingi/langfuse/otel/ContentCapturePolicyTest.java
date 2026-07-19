package io.github.chomingi.langfuse.otel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentCapturePolicyTest {

    @Test
    void metadataOnlyIsTheSafeFiniteDefault() {
        ContentCapturePolicy policy = ContentCapturePolicy.metadataOnly();

        assertThat(policy.isInputCaptureEnabled()).isFalse();
        assertThat(policy.isOutputCaptureEnabled()).isFalse();
        assertThat(policy.getMaxLength()).isEqualTo(ContentCapturePolicy.DEFAULT_MAX_LENGTH);
        assertThat(policy.getMaxLength()).isPositive();
    }

    @Test
    void inputAndOutputCanBeEnabledIndependently() {
        ContentCapturePolicy inputOnly = ContentCapturePolicy.builder()
                .captureInput(true)
                .build();
        ContentCapturePolicy outputOnly = ContentCapturePolicy.builder()
                .captureOutput(true)
                .build();

        assertThat(inputOnly.capture(ContentCaptureType.INPUT, "input")).isEqualTo("input");
        assertThat(inputOnly.capture(ContentCaptureType.OUTPUT, "output")).isNull();
        assertThat(outputOnly.capture(ContentCaptureType.INPUT, "input")).isNull();
        assertThat(outputOnly.capture(ContentCaptureType.OUTPUT, "output")).isEqualTo("output");
    }

    @Test
    void truncatesAfterRedactionWithoutSplittingSurrogatePairs() {
        ContentCapturePolicy policy = ContentCapturePolicy.builder()
                .captureInput(true)
                .maxLength(2)
                .redactor((type, content) -> "a\uD83D\uDE00" + content)
                .build();

        assertThat(policy.capture(ContentCaptureType.INPUT, "tail")).isEqualTo("a");
    }

    @Test
    void rejectsUnboundedOrEmptyLengthConfiguration() {
        assertThatThrownBy(() -> ContentCapturePolicy.builder().maxLength(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }
}
