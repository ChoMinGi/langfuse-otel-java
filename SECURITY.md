# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT open a public issue.**
2. Use [GitHub's private vulnerability reporting](https://github.com/ChoMinGi/langfuse-otel-java/security/advisories/new).
3. Include a description of the vulnerability, steps to reproduce, and potential impact.

We aim to acknowledge reports within 72 hours and provide an initial severity assessment within seven days. These are best-effort targets, not a service-level agreement; the remediation timeline depends on impact and release risk.

## Scope

This library transmits LLM trace data to Langfuse via OTLP/HTTP. The following data may be sent as span attributes:

| Data Type | When Sent | How to Opt Out |
|-----------|-----------|----------------|
| Prompts & completions | Only when automatic input/output capture is explicitly enabled, or the core fluent API is called explicitly | Keep `langfuse.content.capture-input/output=false` (default) |
| Embedding input text | Only when automatic input capture is explicitly enabled | Keep `langfuse.content.capture-input=false` (default) |
| Image prompts | Only when automatic input capture is explicitly enabled | Keep `langfuse.content.capture-input=false` (default) |
| Principal name | Only when HTTP context user capture is explicitly enabled | Keep `langfuse.context.capture-user-id=false` (default) |
| HTTP session ID | Only when HTTP context session capture is explicitly enabled | Keep `langfuse.context.capture-session-id=false` (default) |
| Exception message and stack trace | Only when each exception detail is explicitly enabled | Keep `langfuse.exception.capture-message/stack-trace=false` (default) |
| Token counts | All model calls | Sent when available |
| Model parameters | All model calls | Sent when available |

Automatic content capture is metadata-only by default. Enabling it does not require or install an application redactor: with zero `ContentRedactor` beans, the identity redactor exports enabled content unchanged except for truncation to `langfuse.content.max-length` (default `8192`). Exactly one bean processes content before truncation. Multiple beans fail closed and drop automatic content; a redactor failure or `null` result also drops the affected value. Treat one reviewed, thread-safe redactor as mandatory in production whenever captured content may be sensitive.

Spring AI streaming never retains an unbounded completion. If the raw completion exceeds `langfuse.content.max-length` before terminal redaction, the output attribute is dropped rather than exporting a pre-redaction prefix. This trades long-stream content visibility for a fail-closed privacy boundary.

Explicit core fluent calls such as `generation.input(value)` are treated as an intentional caller opt-in and do not use the automatic capture policy.

HTTP session IDs can be bearer credentials in some deployments. Do not enable session capture unless the value is non-secret and approved for export. Prefer an application-defined opaque analytics session identifier set explicitly on a trace.

Exceptions frequently contain response bodies, prompts, URLs, or credentials. Automatic instrumentation records only `exception.type` by default. Enabling message or stack capture with zero `ExceptionRedactor` beans uses the identity redactor, so the selected details are exported unchanged except for the independent `langfuse.exception.max-length` limit. Exactly one bean processes details before truncation. Multiple beans, redactor failures, or a `null` result fail closed. Treat one reviewed, thread-safe exception redactor as mandatory in production before enabling sensitive details.

Captured stacks contain exception types and frames but omit throwable messages from the top-level exception, causes, and suppressed exceptions. Message capture remains an independent opt-in.

### API Keys

- Langfuse public/secret keys are used for OTLP authentication via `Authorization: Basic` header.
- **Never commit keys to version control.** Use environment variables or a secret manager.
- The library does not intentionally log or expose API keys. Standalone mode retains them in process memory as an exporter authentication header for the lifetime of the owned SDK.

### Transport

Standalone communication uses HTTPS/TLS by default. The configured host must be an absolute HTTP(S) URI with a network host and cannot contain user-info, a query, or a fragment. Plaintext HTTP is rejected unless local development explicitly enables `.allowInsecureHttpForDevelopment(true)` or `langfuse.allow-insecure-http-for-development=true`, and even then only `localhost` or literal loopback addresses are accepted. Never combine that option with production credentials.

The standalone transport contract test also verifies that cross-origin redirects do not forward the Basic `Authorization` header. This is a regression guard for the bundled OpenTelemetry HTTP exporter; deployments should still avoid redirects and configure the final HTTPS origin directly.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.2.x   | Production preview; maintained from 0.2.0 |
| 0.1.x   | Security fixes until 0.2.0 is released |

When `0.3.0` moves the starter to Spring Boot 4 and Spring AI 2, `0.2.x` will remain in maintenance for six months. That window covers critical vulnerabilities and regressions owned by this library; it does not extend the support lifecycle of Spring Boot 3 or other upstream dependencies.
