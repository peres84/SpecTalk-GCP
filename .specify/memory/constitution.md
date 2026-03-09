# SpecTalk Voice Agent Constitution

Bare-minimum, non-negotiable requirements for building and operating a voice agent with strong security posture and **Google (email/OAuth) authentication**.

## Core Principles

### 1) Authenticated Access Only
- All user access to the Voice Agent service requires **Google authentication**.
- Every request/session MUST resolve a stable `user_id` from the verified token (e.g., Google `sub`).
- Anonymous, shared, or “guest” access is not allowed.

### 2) Verify Tokens Server-Side
- The Voice Agent service MUST verify bearer tokens server-side (never trust client-asserted identity).
- Reject missing/expired/invalid tokens with 401.
- Optional but supported: allowlist by exact email and/or domain allowlist (e.g., `@company.com`).

### 3) Least-Privilege Tools
- The main agent (Jarvis) may have tools enabled by default, but tools MUST be permissioned.
- Tool calls that can touch files, shell, network, or credentials must be gated by:
  - authenticated user identity, and
  - explicit policy (allowlist / capability flags), and
  - safe defaults (deny by default).

### 4) Private Orchestrator Boundary
- The Orchestrator service is **private** and MUST NOT be exposed to the public internet.
- Voice Agent ↔ Orchestrator communication MUST go over a private network (e.g., Tailscale).
- Orchestrator endpoints MUST require a shared secret (bearer token) in addition to network isolation.

### 5) Secrets Are Never Stored in Git
- Never commit `.env`, API keys, OAuth client secrets, or service account keys.
- Load secrets via environment variables and/or managed secret stores.
- Logs must never include tokens, secrets, or raw authorization headers.

### 6) Secure Transport & Basic Abuse Controls
- External endpoints MUST use TLS (HTTPS/WSS).
- Apply basic abuse protection: request size limits, timeouts, and rate limiting per user.

## Security Requirements (Minimum)

### Authentication
- Supported identity: Google Sign-In (email/OAuth).
- Token verification requirements:
  - Validate signature and expiry.
  - Validate audience/client ID.
  - Extract `sub` as `user_id`.

### Authorization
- Define a simple authorization policy:
  - `allow_all_authenticated=false` by default
  - enable either `allowed_emails` or `allowed_domains` for access control

### Data Handling
- Do not store raw audio by default.
- If transcripts or specs are persisted, store the minimum necessary and associate them with `user_id`.

### Logging
- Use structured logs (JSON or key-value) with:
  - request/session id
  - `user_id` (never email unless necessary)
  - event type (auth_success/auth_failure/tool_call/orchestrator_start)

### Dependencies
- Pin dependencies (lockfile/requirements) and keep them minimal.
- No unreviewed “auto-update” dependencies in production.- **All Python package management MUST use `uv sync`** — never use `pip install`, `pip install -r requirements.txt`, or any other package manager for Python dependencies. `uv sync` is the only approved method for installing and syncing Python packages in this project.
## Quality Gates (Minimum)

- Authentication verification must be covered by at least one automated test (happy path + invalid token).
- Any new external endpoint must have:
  - auth enforced,
  - input validation,
  - timeouts.

## Governance

- This constitution overrides convenience and speed.
- Any exception must be documented with:
  - why it’s needed,
  - scope and risk,
  - rollback plan.

**Version**: 1.0.0 | **Ratified**: 2026-03-02 | **Last Amended**: 2026-03-02
