# Contributing

Thanks for your interest in contributing to langfuse-otel-java!

## Development Setup

### Prerequisites

- Java 17+
- Maven 3.8+
- A Langfuse account (cloud or self-hosted) for integration tests

### Build

```bash
mvn compile
```

### Run Tests

Unit tests (no external dependencies):

```bash
mvn test
```

Integration tests (requires Langfuse API keys):

```bash
export LANGFUSE_PUBLIC_KEY=pk-lf-...
export LANGFUSE_SECRET_KEY=sk-lf-...
export LANGFUSE_HOST=https://cloud.langfuse.com

mvn test -Dgroups=integration
```

### Project Structure

```
langfuse-otel-java/
├── langfuse-otel-core/                  # Core library (Java 11+)
│   └── io.github.chomingi.langfuse.otel
│       ├── LangfuseOtel                 # Main entry point (Builder)
│       ├── LangfuseTrace                # Trace wrapper
│       ├── LangfuseGeneration           # LLM generation wrapper
│       ├── LangfuseSpan                 # General span wrapper
│       ├── LangfuseContext              # ThreadLocal context
│       ├── LangfuseAttributes           # OTel attribute constants
│       └── LangfusePromptHelper         # Prompt integration
│
├── langfuse-otel-spring-boot-starter/   # Spring Boot starter (Java 17+)
│   └── io.github.chomingi.langfuse.otel.spring
│       ├── LangfuseOtelAutoConfiguration
│       ├── LangfuseOtelProperties
│       ├── SpringAiAutoConfiguration    # Spring AI auto-instrumentation
│       ├── SpringAiInstrumentationAspect
│       ├── LangChain4jAutoConfiguration # LangChain4j auto-instrumentation
│       ├── LangChain4jInstrumentationAspect
│       ├── LangfuseContextFilter        # HTTP context propagation
│       └── annotation/
│           ├── ObserveGeneration         # @ObserveGeneration
│           └── ObserveGenerationAspect
│
└── examples/                            # Example applications
    ├── spring-ai-example/
    └── langchain4j-example/
```

## Guidelines

- Open an issue before starting work on a new feature
- Keep PRs focused — one feature/fix per PR
- Add tests for new functionality
- Follow existing code style (no Lombok, no excessive comments)
- Core module must stay Java 11 compatible
- Spring Boot starter targets Java 17+

## Adding a New Auto-Instrumentation

To add support for a new LLM framework:

1. Create an `*InstrumentationAspect.java` in the spring starter module
2. Create an `*AutoConfiguration.java` with `@ConditionalOnClass` for the framework's main class
3. Add the framework as an `<optional>` dependency in `pom.xml`
4. Register the auto-configuration in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
5. Add an example in `examples/`
