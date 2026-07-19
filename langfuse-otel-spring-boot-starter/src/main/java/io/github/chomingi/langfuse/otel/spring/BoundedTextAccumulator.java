package io.github.chomingi.langfuse.otel.spring;

/**
 * Thread-safe streaming text accumulator that never retains more than its configured limit.
 * Callers must drop captured output when {@link #overflowed()} is true so redaction always sees
 * either the complete raw value or no value at all.
 */
final class BoundedTextAccumulator implements CharSequence {

    private final int maxLength;
    private final StringBuilder value;
    private int observedLength;
    private boolean overflowed;
    private char pendingHighSurrogate;

    BoundedTextAccumulator(int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be greater than zero");
        }
        this.maxLength = maxLength;
        this.value = new StringBuilder(Math.min(maxLength, 256));
    }

    synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }

        if (!overflowed) {
            if (text.length() > maxLength - observedLength) {
                overflowed = true;
            } else {
                observedLength += text.length();
            }
        }

        int startIndex = 0;
        if (pendingHighSurrogate != 0) {
            char highSurrogate = pendingHighSurrogate;
            pendingHighSurrogate = 0;
            if (Character.isLowSurrogate(text.charAt(0))) {
                if (maxLength - value.length() < 2) {
                    return;
                }
                value.append(highSurrogate).append(text.charAt(0));
                startIndex = 1;
            }
        }

        if (startIndex >= text.length() || value.length() >= maxLength) {
            return;
        }

        int endIndex = Math.min(text.length(), startIndex + maxLength - value.length());
        if (endIndex < text.length()
                && endIndex > startIndex
                && Character.isHighSurrogate(text.charAt(endIndex - 1))
                && Character.isLowSurrogate(text.charAt(endIndex))) {
            endIndex--;
        }

        if (endIndex == text.length()
                && endIndex > startIndex
                && Character.isHighSurrogate(text.charAt(endIndex - 1))) {
            pendingHighSurrogate = text.charAt(endIndex - 1);
            endIndex--;
        }
        value.append(text, startIndex, endIndex);
    }

    synchronized boolean overflowed() {
        return overflowed;
    }

    @Override
    public synchronized int length() {
        return value.length();
    }

    @Override
    public synchronized char charAt(int index) {
        return value.charAt(index);
    }

    @Override
    public synchronized CharSequence subSequence(int start, int end) {
        return value.subSequence(start, end);
    }

    @Override
    public synchronized String toString() {
        return value.toString();
    }
}
