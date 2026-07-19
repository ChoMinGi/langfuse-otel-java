package io.github.chomingi.langfuse.otel.spring;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedTextAccumulatorTest {

    @Test
    void appendNeverRetainsMoreThanTheConfiguredLimit() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(5);

        accumulator.append("abc");
        accumulator.append("defgh");
        accumulator.append("ignored");

        assertThat(accumulator).hasToString("abcde");
        assertThat(accumulator.length()).isEqualTo(5);
        assertThat(accumulator.overflowed()).isTrue();
    }

    @Test
    void truncationDoesNotSplitASurrogatePair() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(2);

        accumulator.append("a\uD83D\uDE00");

        assertThat(accumulator).hasToString("a");
        assertThat(accumulator.length()).isEqualTo(1);
        assertThat(accumulator.overflowed()).isTrue();
    }

    @Test
    void surrogatePairSplitAcrossChunksIsRetainedWhenItFits() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(2);

        accumulator.append("\uD83D");
        accumulator.append("\uDE00");

        assertThat(accumulator).hasToString("\uD83D\uDE00");
        assertThat(accumulator.length()).isEqualTo(2);
        assertThat(accumulator.overflowed()).isFalse();
    }

    @Test
    void surrogatePairSplitAcrossChunksIsDroppedWhenItCrossesTheLimit() {
        BoundedTextAccumulator accumulator = new BoundedTextAccumulator(1);

        accumulator.append("\uD83D");
        accumulator.append("\uDE00");

        assertThat(accumulator).hasToString("");
        assertThat(accumulator.overflowed()).isTrue();
    }
}
