## Summary

<!-- What does this PR do and why? -->

## Changes

-

## Testing

<!-- How did you verify this works? -->

## Checklist

- [ ] Compiles without errors (`mvn compile`)
- [ ] All unit tests pass (`mvn test`)
- [ ] New functionality has test coverage
- [ ] Core module stays Java 11 compatible
- [ ] No hardcoded API keys or secrets
- [ ] Tracing wrappers handle setup failures gracefully (no app-breaking exceptions)
- [ ] Streaming instrumentation uses raw `Span` without `makeCurrent()` (see DESIGN.md #13)
