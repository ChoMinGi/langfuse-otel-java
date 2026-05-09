# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT open a public issue.**
2. Use [GitHub's private vulnerability reporting](https://github.com/ChoMinGi/langfuse-otel-java/security/advisories/new) or email the maintainer directly.
3. Include a description of the vulnerability, steps to reproduce, and potential impact.

We will respond within 48 hours and work to release a fix promptly.

## Scope

This library transmits LLM trace data to Langfuse via OTLP/HTTP. The following data may be sent as span attributes:

| Data Type | When Sent | How to Opt Out |
|-----------|-----------|----------------|
| Prompts & completions | Chat model calls (sync & streaming) | Don't call `.input()` / `.output()`, or don't use auto-instrumentation |
| Embedding input text | Embedding model calls | Same as above |
| Image prompts | Image model calls | Same as above |
| Token counts | All model calls | Always sent if available (not sensitive) |
| Model parameters | All model calls | Always sent if available (not sensitive) |

### API Keys

- Langfuse public/secret keys are used for OTLP authentication via `Authorization: Basic` header.
- **Never commit keys to version control.** Use environment variables or a secret manager.
- The library never logs, stores, or exposes API keys — they are only passed to the OTel OTLP exporter.

### Transport

All communication with Langfuse Cloud uses HTTPS/TLS. Self-hosted deployments should also use TLS.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |
