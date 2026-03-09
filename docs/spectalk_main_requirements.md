# SpecTalk v1 Main Requirements

## Purpose

This document is the simplified source of truth for SpecTalk v1.

It is written to be handed to a coding agent so implementation can start without reading all architecture notes first.

---

## Product Goal

Build a voice-first software assistant that lets a user speak from a frontend app or Meta glasses app, send audio to a backend on Google Cloud, use tools in the background, run coding work inside an isolated Google Cloud sandbox, and report progress or execution problems back to the user in plain language.

---

## v1 Scope

SpecTalk v1 should do these things well:

1. Receive audio from:
   - web frontend app
   - Meta glasses companion app
2. Stream audio to a Google Cloud voice backend
3. Let one visible assistant talk to the user
4. Allow the assistant to call tools through a controlled backend
5. Send coding work to an isolated Google Cloud sandbox
6. Run an orchestrator that tracks job state, execution output, and blockers
7. Return useful status and error messages to the user, including:
   - missing API keys
   - missing secrets
   - missing repo access
   - test failures
   - dependency/install failures
   - approval-required actions

---

## v1 Product Decisions

To keep the first version clear and buildable, use these decisions:

- Cloud provider: Google Cloud only
- Voice backend: one GCP-hosted voice agent service
- Public clients: web app and Meta glasses companion app
- Main backend pattern: voice service + control plane + coding runner
- Coding sandbox: isolated Google Cloud runner
- Runner choice for v1: Compute Engine VM with persistent disk
- Execution model: asynchronous jobs with status events
- User-facing assistant: one assistant only
- Tool access: behind the backend, never directly from the client

---

## Main Components

### 1. Frontend app

Responsibilities:

- capture microphone audio
- authenticate the user
- stream audio to the voice backend
- display transcript, status, and blockers
- show when the agent is listening, thinking, running, blocked, or done

### 2. Meta glasses companion app

Responsibilities:

- pair with glasses or mobile audio input
- forward audio to the same voice backend
- receive replies and status updates
- stay lightweight; do not run orchestration logic locally

### 3. Voice agent service on Google Cloud

Responsibilities:

- manage live audio sessions
- transcribe and respond in realtime
- keep the assistant conversational
- decide whether to answer directly or create a background job
- never run long coding work in the realtime path

### 4. Control plane

Responsibilities:

- create and track jobs
- maintain session state
- register available tools
- route requests to the correct tool or runner
- normalize progress and errors into a common format
- decide what message should be shown or spoken to the user

### 5. Tool layer

Responsibilities:

- expose tools to the agent in a controlled way
- include regular tools such as memory, search, repo access, and status lookup
- include one main coding tool: Google Cloud coding sandbox
- enforce permissions and approval rules

### 6. Coding sandbox on Google Cloud

Responsibilities:

- run code generation and command execution in isolation
- access project workspace and repository
- run tests, installs, and build commands
- persist workspace state between runs
- never be directly exposed to public clients

### 7. Orchestrator

Responsibilities:

- accept coding jobs from the control plane
- prepare workspace, tools, and credentials
- execute the task in the coding sandbox
- capture progress, logs, blockers, and results
- classify failures into user-friendly categories
- send status events back to the control plane

---

## Primary User Flow

1. User speaks from the web app or Meta glasses app.
2. Audio is streamed to the GCP voice agent.
3. The assistant answers immediately if the request is simple.
4. If the request needs coding or longer work, the assistant creates a job.
5. The control plane routes the job to the orchestrator.
6. The orchestrator starts or reuses the Google Cloud runner.
7. The runner executes the job with the allowed tools.
8. The orchestrator sends progress events back.
9. The assistant tells the user what is happening.
10. If execution is blocked, the assistant tells the user exactly what is missing.

---

## Functional Requirements

### Audio and conversation

- Support live audio input from the web app
- Support live audio input from the Meta glasses companion app
- Maintain one active assistant persona
- Support interruption and continue talking after background jobs start

### Authentication

- Require user authentication before using the system
- Bind all jobs to a user and session
- Separate end-user auth from runner/service auth

### Job handling

- Support sync actions for quick tool calls
- Support async jobs for coding and long-running work
- Store job state and event history

### Tool execution

- Agent can access tools only through the backend
- Each tool call must declare input, output, and permission profile
- Dangerous actions must produce approval events when required

### Coding sandbox

- Use an isolated GCP runner with persistent workspace storage
- Support repo checkout, branch creation, file edits, tests, and commits
- Support runtime secret injection
- Do not store secrets in workspace files

### Orchestrator and failures

- Track states such as queued, running, blocked, completed, failed
- Convert raw execution failures into structured blocker types
- Return actionable user-facing messages

Example blocker categories:

- `missing_secret`
- `missing_env_var`
- `repo_auth_failed`
- `tool_auth_failed`
- `dependency_install_failed`
- `test_failed`
- `approval_required`
- `runner_unavailable`

### User feedback

- User must always know whether the system is listening, processing, running, blocked, or finished
- Blocker messages must explain what the user needs to do next
- The assistant should not expose raw stack traces by default

---

## Minimal Data Contracts

### Session

- `session_id`
- `user_id`
- `transport`
- `status`

### Job

- `job_id`
- `session_id`
- `user_id`
- `type`
- `status`
- `requested_tool`
- `workspace_id`
- `created_at`
- `updated_at`

### Status event

- `event_type`
- `job_id`
- `status`
- `message`
- `details`
- `timestamp`

### Blocker

- `job_id`
- `blocker_type`
- `message`
- `user_action_required`
- `resolution_options`

---

## Suggested v1 Architecture

### Public layer

- web frontend
- Meta glasses companion app
- GCP voice agent service

### Private backend layer

- control plane
- tool registry
- orchestrator API

### Private execution layer

- one Compute Engine runner VM
- one persistent disk for workspaces
- one coding sandbox runtime on the VM

---

## Recommended Build Order

1. Voice interaction end-to-end
2. Control plane and job model
3. Tool registry and tool contract
4. Google Cloud coding runner
5. Orchestrator with status and blocker flow
6. Frontend status/control panel
7. Meta glasses companion integration

---

## What To Improve From The Current Docs

The existing workflow documents have good detail, but they are too broad for implementation kickoff.

Main improvements:

1. Pick one cloud path for v1
   - Current docs mix GCP, AWS, multiple providers, and several deployment choices
   - For v1, use Google Cloud only

2. Pick one coding runner strategy
   - Current docs mention Cloud Workstations and Compute Engine
   - For v1, choose Compute Engine VM plus persistent disk

3. Reduce visible architecture complexity
   - Do not begin with many visible specialist agents
   - Keep one assistant and one background orchestrator

4. Define the blocker protocol early
   - Missing keys, missing secrets, and failed execution need a strict schema
   - This is one of the most important product behaviors

5. Focus on the real user entrypoints
   - web frontend
   - Meta glasses companion app
   - not every transport from day one

6. Make one document the implementation source of truth
   - The current ideas are spread across multiple long documents
   - This file should be the short handoff brief

---

## Non-Goals For v1

- Multi-cloud support
- Multiple coding runner types active at the same time
- Full swarm of public-facing sub-agents
- Direct client access to tools or shell
- Production deployment automation in the first pass

---

## Definition of Done For v1

SpecTalk v1 is successful when:

- the user can talk from the web app or Meta glasses path
- the backend receives audio and responds conversationally
- the assistant can create a coding job
- the orchestrator runs that job in the GCP sandbox
- progress is returned to the user
- execution blockers are returned clearly
- the user can understand what failed and what action is needed next

