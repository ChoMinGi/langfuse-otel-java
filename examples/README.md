# Examples

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
