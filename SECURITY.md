# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability, please report it responsibly:

1. **Do NOT open a public issue.**
2. Email the maintainer directly or use GitHub's private vulnerability reporting feature.
3. Include a description of the vulnerability, steps to reproduce, and potential impact.

We will respond within 48 hours and work to release a fix promptly.

## Scope

This library transmits LLM trace data (prompts, completions, token counts) to Langfuse via OTLP/HTTP. Security considerations:

- **API keys**: Langfuse public/secret keys are used for OTLP authentication. Never commit keys to version control. Use environment variables or secret management.
- **Content capture**: By default, prompt and completion content is sent as span attributes. For sensitive data, consider not calling `.input()` / `.output()` or implementing content filtering.
- **Transport**: All communication with Langfuse Cloud uses HTTPS/TLS.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |
