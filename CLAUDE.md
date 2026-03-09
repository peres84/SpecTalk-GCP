# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**SpecTalk** is a voice-first software creation system that keeps users in conversational flow while complex tasks run in the background. The architecture separates conversational orchestration from execution orchestration.

### Core Design Principle
> The user should be able to keep talking naturally while slow or complex work continues in the background.

**Three major layers:**
1. **Realtime Voice Runtime** — manages audio, turn-taking, interruption, and natural conversation
2. **Control Plane** — manages jobs, memory, routing, approvals, and event flow
3. **Execution Plane** — runs specialist teams, tools, builds, searches, and background work

Key reference: `docs/workflow-08.03.26/spectalk_v_1_architecture_doc.md`

---

## Codebase Structure

### `voice_providers_sockets/`
The realtime voice runtime layer. Each provider is a transport adapter.

- **`amazon_websocket/`** — FastAPI WebSocket backend using Amazon Nova Sonic 2 (Bedrock)
  - `main.py` — FastAPI app entry point with lifespan management
  - `src/providers/nova_sonic.py` — Bedrock bidirectional streaming adapter
  - `src/orchestrator_client.py` — HTTP client for control plane (POST /start, GET /status, POST /cancel)
  - `src/ws_endpoints/websocket.py` — WebSocket handler for real-time voice
  - `src/tools.py` — Tool registry for the voice agent
  - `src/core_specs/` — Configuration and data loading
  - `tests/` — unittest-based test suite

- **`google_websocket/`** — Placeholder for Google Cloud voice provider (not yet implemented)

### `scripts/`
Utility and test scripts.

- **`amazon_tests/`** — Examples and tests for Amazon voice provider integration
- **`gemini_test/`** — Examples for Google Gemini API integration
- **`utils/`** — Common utilities (logging, validation)

### `docs/`
Architecture and planning documentation.

- **`workflow-08.03.26/`** — Complete SpecTalk v1 specification
  - `spectalk_v_1_architecture_doc.md` — Core architectural principles and design
  - `spectalk_orchestrator_specification.md` — Detailed orchestrator contract
  - `spectalk_gcp_coding_execution_phases.md` — GCP execution layer implementation guide

---

## Key Architectural Concepts

### Voice Provider Pattern
Each provider (Amazon, Google, etc.) implements the same contract:
- `start_session(user_id, system_prompt)` — Initialize voice session
- `send_audio_chunk(pcm_bytes)` — Stream audio input
- `events()` — Async generator for response events
- `end_session()` — Cleanup

See: `voice_providers_sockets/amazon_websocket/src/providers/nova_sonic.py`

### Control Plane Communication
The voice runtime does NOT speak directly to users about execution. Instead:
- Voice runtime submits work to control plane
- Control plane routes to orchestrator
- Orchestrator publishes structured events (job.created, job.progress, job.blocked, etc.)
- Control plane decides what to tell Jarvis
- Jarvis converts events into natural spoken responses

See: `voice_providers_sockets/amazon_websocket/src/orchestrator_client.py` (simple reference implementation)

### Capability Model
Work is classified into types by execution latency:

| Type | Latency | Example |
|------|---------|---------|
| `sync_tool` | <800ms | fetch memory, read config |
| `async_job` | 1-4s | search, video generation |
| `team_request` | 2-10s | dev team review |
| `interactive_session` | unbounded | IDE control, shell bridge |

Only jobs under ~800ms should happen inline in voice turns. Longer work becomes background jobs.

### Jarvis (Single Persona)
- The only voice assistant persona visible to users
- Does NOT directly run slow tasks
- Decides what to say, when, and how much to expose
- Reads memory, retrieves status, asks the next useful question
- Under strict turn budget: 0–500ms for immediate response, 0.5–2s with optional fast tool, >2s creates background job

---

## Common Development Commands

### Running the Amazon voice provider locally

```bash
cd voice_providers_sockets/amazon_websocket

# Install dependencies (if needed)
pip install -r requirements.txt  # if it exists
# OR check .venv in gemini_test for reference setup

# Set environment variables
export ORCHESTRATOR_URL="http://localhost:8000"
export ORCHESTRATOR_TOKEN="your-token"
export AWS_REGION="us-east-1"
export AWS_BEDROCK_MODEL_ID="amazon.nova-2-sonic-v1:0"

# Run the server
python main.py
# Runs on configured port (see src/core_specs/configuration/config_loader.py)
```

### Running tests

```bash
cd voice_providers_sockets/amazon_websocket

# Run all tests
python -m unittest discover tests -v

# Run a specific test file
python -m unittest tests.test_orchestrator_client -v

# Run a specific test class
python -m unittest tests.test_orchestrator_client.TestOrchestratorClient -v
```

### Linting and validation

```bash
# Check for common issues
python -m pylint voice_providers_sockets/amazon_websocket/src

# Validate configuration loading
python -c "from src.core_specs.configuration.config_loader import config_loader; print(config_loader)"
```

---

## Architecture Decision Log

### 1. Separate Conversational from Execution Orchestration
The voice runtime should NOT block waiting for build tasks. Instead, conversational orchestration (Jarvis) is a separate concern from execution orchestration (Control Plane → Orchestrator → Runners).

### 2. Single Assistant Persona
Multiple specialist teams exist internally, but the user only sees "Jarvis." All internal reasoning is hidden; only synthesized questions and decisions reach the user.

### 3. Event-Driven Status Model
Rather than synchronous polling, background systems publish structured events (job.created, job.progress, job.blocked, job.completed). This decouples voice from execution and keeps Jarvis responsive.

### 4. Distributed Workspace Model (v1+)
For SpecTalk v1, repos live in ephemeral runners with persistent workspace directories. The control plane and orchestrator manage workspace lifecycle. This isolates dangerous coding operations from the public voice API.

### 5. Provider Abstraction
Voice providers (Nova Sonic, GPT-4o Realtime, Gemini Live) are swappable adapters. Each implements the same async contract. Configuration controls which provider is active.

---

## Security and Trust Boundaries

### Public Zone
- Voice transports (WebSocket, WebRTC, Twilio)
- User-facing session APIs
- Client authentication

### Private Control Zone
- Control Plane (event routing, job management)
- Token minting
- Approval policy enforcement

### Private Execution Zone
- Isolated coding runners
- Repository workspaces
- Shell execution
- Build tools

**Key rule:** The public voice runtime must NEVER directly expose shell or git access. All execution happens through the control plane.

See: `docs/workflow-08.03.26/spectalk_gcp_coding_execution_phases.md` (Phase 1)

---

## Key Files and Responsibilities

| File | Purpose |
|------|---------|
| `voice_providers_sockets/amazon_websocket/main.py` | FastAPI app setup; handles startup/shutdown lifecycle |
| `src/providers/nova_sonic.py` | Bedrock bidirectional streaming; audio codec handling; session lifecycle |
| `src/orchestrator_client.py` | Simple HTTP interface to build orchestrator; no logging of secrets |
| `src/ws_endpoints/websocket.py` | WebSocket connection handler; speech-to-text/text-to-speech flow |
| `src/tools.py` | Tool registry and execution; MCP server integration |
| `src/core_specs/configuration/config_loader.py` | Environment-based config; read at startup, immutable at runtime |
| `tests/test_orchestrator_client.py` | Mock-based unit tests for control plane communication |

---

## Important Patterns

### Configuration Management
- All config loaded at startup from environment variables
- Config is immutable at runtime
- No hardcoded credentials; all secrets come from environment or secret manager
- See: `src/core_specs/configuration/config_loader.py`

### Logging
- Custom logger with configurable handlers
- Never log specs, tokens, or sensitive data (I6 compliance)
- See: `src/utils/custom_logger.py`

### Async/Streaming
- Nova Sonic and similar providers use Python async/await
- Events streamed via `asyncio.Queue` to avoid blocking
- Test mocking uses `AsyncMock` for provider simulation

### Test Patterns
- Unit tests use `unittest` module with `unittest.mock`
- No pytest dependency; uses standard library
- Orchestrator client tests mock HTTP requests

---

## Next Phases (From Architecture Doc)

### Phase 2 — Control Plane Implementation
- [ ] Job manager: intake, validation, state machine
- [ ] Event bus: publish/subscribe for job events
- [ ] Question arbiter: rank and filter questions to user
- [ ] Session store: maintain conversation context

### Phase 3 — Orchestrator (Execution Layer)
- [ ] Runner selection and scheduling
- [ ] Workspace binding and lifecycle
- [ ] Job execution protocol (Claude Code / Codex launch)
- [ ] Approval and blocker flow

### Phase 4 — Dev Team (Specialist Workflow)
- [ ] Planner: interpret request and define workstreams
- [ ] Architecture/Security/Backend reviewers
- [ ] Question synthesizer: merge findings into blocking questions
- [ ] Spec patcher: incremental spec updates

### Phase 5 — Google Cloud Voice (GCP)
- [ ] Google Cloud Dialogflow or Gemini Live integration
- [ ] Cloud Workstations or Compute Engine runner
- [ ] Vertex AI authentication for Claude Code
- [ ] Secret Manager integration for credentials

---

## References

### Core Architecture
- `docs/workflow-08.03.26/spectalk_v_1_architecture_doc.md` — Primary design document
- Positions SpecTalk as **event-driven, multi-runtime system** with three layers

### Execution Layer
- `docs/workflow-08.03.26/spectalk_orchestrator_specification.md` — Detailed orchestrator API
- Specifies job schema, runner selection, workspace binding, state machine

### GCP Implementation
- `docs/workflow-08.03.26/spectalk_gcp_coding_execution_phases.md` — 16-phase GCP implementation guide
- Covers base image, authentication, secret management, workspace durability

### Helpful Concepts
- **Latency Classes:** Sync vs async vs long-running work determines voice UX
- **Turn Budget:** 0–500ms acknowledge, 0.5–2s one tool, >2s background job
- **Capability Model:** Categorize work by type (sync_tool, async_job, team_request, interactive_session)
- **Question Arbitration:** Not all internal questions reach the user; only the minimum blocking ones

---

## Running First-Time Setup (Optional)

If you're setting up the environment from scratch:

```bash
# Clone repo (already done)
cd voice_providers_sockets/amazon_websocket

# Create virtual environment (if needed)
python -m venv .venv
source .venv/bin/activate  # or .\.venv\Scripts\activate on Windows

# Install dependencies from requirements (if repo adds one later)
# pip install -r requirements.txt

# Create .env file in project root with:
# ORCHESTRATOR_URL=http://localhost:8000
# ORCHESTRATOR_TOKEN=dev-token
# AWS_REGION=us-east-1
# AWS_BEDROCK_MODEL_ID=amazon.nova-2-sonic-v1:0
# AWS_ACCESS_KEY_ID=... (from AWS account)
# AWS_SECRET_ACCESS_KEY=... (from AWS account)

# Test configuration loads
python -c "from src.core_specs.configuration.config_loader import config_loader; print('Config loaded OK')"

# Run server
python main.py
```

---

## Troubleshooting

### Issue: "ORCHESTRATOR_URL is not set"
- Check that environment variable `ORCHESTRATOR_URL` is exported before running the app
- Orchestrator client expects this to be set (see `src/orchestrator_client.py` line 17-18)

### Issue: "Bedrock client initialization failed"
- Verify AWS credentials are set: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`
- Check that Bedrock is available in your AWS region
- Model ID defaults to `amazon.nova-2-sonic-v1:0` but can be overridden

### Issue: Tests fail with "No provider initialized"
- Tests mock the provider; check `tests/test_orchestrator_client.py` for AsyncMock patterns
- Ensure unittest imports are correct: `from unittest.mock import AsyncMock, patch`

---

## Future Enhancements

1. **Google Cloud Provider** — Implement Gemini Live / Dialogflow adapter in `google_websocket/`
2. **Control Plane Service** — Separate service with job, event, memory, and approval APIs
3. **Orchestrator Service** — Runner scheduling, workspace binding, execution coordination
4. **Multi-Provider Fallback** — Try next provider if one fails during a turn
5. **Approval Workflow** — Integration with control plane for dangerous operations
6. **Memory Persistence** — Session, project, and long-term memory storage
7. **Question Arbitration** — Rank and filter candidate questions from dev team

---

## Final Notes

SpecTalk's strength is keeping voice responsive while running complex background work. Every decision in this codebase should serve that goal:
- Keep the voice loop fast (<2s for interaction)
- Never block voice on slow work
- Let users stay in the conversation while builds, searches, and planning happen
- Feed back only the most useful status updates and questions
- Make approvals and blockers feel natural in conversation

The architecture is designed to scale into a multi-agent, multi-runner system while maintaining the illusion of a single, responsive assistant throughout.
