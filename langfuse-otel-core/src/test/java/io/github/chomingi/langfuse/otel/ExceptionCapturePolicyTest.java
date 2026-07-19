package io.github.chomingi.langfuse.otel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExceptionCapturePolicyTest {

    @Test
    void typeOnlyDisablesMessageAndStackTraceByDefault() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.typeOnly();

        assertThat(policy.isMessageCaptureEnabled()).isFalse();
        assertThat(policy.isStackTraceCaptureEnabled()).isFalse();
        assertThat(policy.capture(ExceptionCaptureType.MESSAGE, "secret message")).isNull();
        assertThat(policy.capture(ExceptionCaptureType.STACK_TRACE, "secret stack")).isNull();
        assertThat(policy.getMaxLength()).isEqualTo(ExceptionCapturePolicy.DEFAULT_MAX_LENGTH);
    }

    @Test
    void detailsAreIndependentOptInsAndAreRedactedBeforeTruncation() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.builder()
                .captureMessage(true)
                .maxLength(12)
                .redactor((type, content) -> "redacted:" + content)
                .build();

        assertThat(policy.capture(ExceptionCaptureType.MESSAGE, "secret"))
                .isEqualTo("redacted:sec");
        assertThat(policy.capture(ExceptionCaptureType.STACK_TRACE, "secret stack")).isNull();
    }

    @Test
    void redactorFailureDropsDetailWithoutThrowing() {
        ExceptionCapturePolicy policy = ExceptionCapturePolicy.builder()
                .captureMessage(true)
                .redactor((type, content) -> {
                    throw new IllegalStateException("redactor failed");
                })
                .build();

        assertThat(policy.capture(ExceptionCaptureType.MESSAGE, "secret")).isNull();
    }

    @Test
    void rejectsNonPositiveMaximumLength() {
        assertThatThrownBy(() -> ExceptionCapturePolicy.builder().maxLength(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }
}
