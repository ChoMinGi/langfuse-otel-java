# Examples

## Start here: first trace

[first-trace](first-trace/) uses the published core artifact from Maven Central, Java 11+,
and fixed synthetic content. It needs only a Langfuse project, checks ingestion through
Observations API v2, and prints a link to the trace. No model API key or reactor install is needed.

## Spring Boot integrations

These Boot 3 web examples retain Spring Framework 6.2.19. Known web-framework findings require
application-specific review and a supported patched framework; see the
[Spring 6.2 security limitations](../SECURITY.md#spring-62-web-framework-limitations).
A clean library SBOM does not certify these standalone web applications as vulnerability-free.

These are standalone consumer projects rather than modules in the main Maven build. Install the
current repository artifacts from the repository root, then provide test credentials:

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

Each process makes live OpenAI calls and sends traces to the configured Langfuse project.
