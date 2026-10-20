# Examples

## Start here: first trace

Run [first-trace](first-trace/) with Java 11+ and a Langfuse project. It downloads the published
library, sends a fixed question and answer, and prints a trace link after checking ingestion.
You do not need a model API key or a local library build.

## Spring Boot integrations

The [Spring AI](spring-ai-example/) and [LangChain4j](langchain4j-example/) examples use OpenAI.
They require Java 17+ and make live model calls.

These Boot 3 web applications still use Spring Framework 6.2.19, which has known vulnerabilities.
Review the [Spring 6.2 limitations](../SECURITY.md#spring-62-web-framework-limitations) before
deploying them; the library's dependency scan does not cover the full example applications.

The examples build separately from the library. From the repository root, install the current
development artifacts and set your credentials:

```bash
./mvnw -B -ntp -DskipTests -Djacoco.skip=true install

export OPENAI_API_KEY="..."
export LANGFUSE_PUBLIC_KEY="pk-lf-..."
export LANGFUSE_SECRET_KEY="sk-lf-..."
# export LANGFUSE_HOST="https://cloud.langfuse.com"
```

Run either example from the repository root:

```bash
./mvnw -f examples/spring-ai-example/pom.xml spring-boot:run
./mvnw -f examples/langchain4j-example/pom.xml spring-boot:run
```

For SDK ownership, content capture, and supported model methods, see the
[Spring Boot guide](../docs/spring-boot.md).
