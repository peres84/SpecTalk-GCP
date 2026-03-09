# SpecTalk v1 Architecture

## Positioning

**SpecTalk** is a voice-first software creation system built around one principle:

> The user should be able to keep talking naturally while slow or complex work continues in the background.

To achieve that, SpecTalk v1 treats the voice assistant as a **realtime conversational runtime**, not as the direct executor of all work.

The system is designed as an **event-driven, multi-runtime architecture** with three major layers:

1. **Realtime Voice Runtime** — manages audio, turn-taking, interruption, and natural conversation
2. **Control Plane** — manages jobs, memory, routing, approvals, and event flow
3. **Execution Plane** — runs specialist teams, tools, builds, searches, and other background work

---

## Design Goals

### Primary goals

- Keep voice interaction responsive under load
- Preserve a single consistent assistant persona
- Allow long-running tasks to continue while the user keeps talking
- Support specialist teams without exposing multi-agent complexity to the user
- Make the system transport-agnostic and provider-agnostic
- Keep local/private execution isolated from public-facing voice infrastructure

### Non-goals for v1

- Fully autonomous free-form agent societies
- Multiple visible assistant personas
- Deep support for many parallel teams from day one
- Rich visual UI orchestration as a hard dependency
- Full multimodal parity across every provider

---

## Product Principle

The user should experience **one assistant**.

Internally, many subsystems may collaborate, but externally the interaction should feel like a conversation with a single capable agent.

This means:

- Jarvis is the only speaking persona
- Specialist agents do not speak directly to the user
- Teams return structured findings, questions, and recommendations
- Jarvis decides what to say, when to say it, and how much to expose

---

## Core Architectural Principle

**Separate conversational orchestration from execution orchestration.**

### Conversational orchestration
Owns:
- listening
- speaking
- turn-taking
- clarification
- interruption handling
- memory retrieval
- progress narration
- asking the next best question

### Execution orchestration
Owns:
- task routing
- specialist collaboration
- code review
- search
- build execution
- file operations
- IDE control
- long-running jobs
- progress events

The voice assistant should not block on execution orchestration.

---

# High-Level Architecture

```text
[Transport Layer]
 WebRTC / WebSocket / Twilio / Companion App
          │
          ▼
[Realtime Voice Runtime]
 audio streaming, VAD, turn manager, interruption manager, TTS/STT/S2S
          │
          ▼
[Jarvis Conversation Engine]
 intent detection, question management, memory access, response policy
          │
          ▼
[Control Plane / Gateway]
 capability registry, job manager, event bus, approvals, session state,
 memory, team dispatcher, status normalization
          │
    ┌─────┼───────────────┬──────────────┬──────────────┐
    ▼     ▼               ▼              ▼              ▼
[Fast Tools] [Async Jobs] [Dev Team] [OpenClaw/MCP] [Status/Event Store]
 search      video gen    planner       IDE/shell      pub-sub stream
 memory      build        reviewers     local bridge   job timeline
```

---

# Runtime Model

## 1. Realtime Voice Runtime

This runtime exists to preserve natural conversation quality.

### Responsibilities
- maintain low-latency audio sessions
- manage VAD, turn detection, and barge-in
- stream partial speech or partial text if supported
- stop speaking immediately when interrupted
- delegate intent handling to Jarvis
- never block the audio loop on slow tools

### Rules
- audio processing must stay lightweight
- background jobs must be offloaded immediately
- no direct multi-agent deliberation in the audio path
- interruptions are first-class events

### Output to upper layers
The realtime runtime should emit structured conversational events such as:

```json
{
  "type": "user.turn.completed",
  "session_id": "sess_123",
  "transcript": "Review my auth design and search best practices",
  "timestamp": "..."
}
```

---

## 2. Jarvis Conversation Engine

Jarvis is the only visible assistant persona.

### Responsibilities
- interpret the user’s request
- decide whether to answer now, ask a question, or start background work
- retrieve short-term and long-term memory
- convert system state into natural spoken language
- ask only the most useful next question
- keep momentum in the conversation

### Jarvis should not
- directly run deep specialist workflows
- directly manage worker lifecycles
- directly poll many tools
- expose internal specialist debates to the user

### Voice-turn budget
Every turn should be handled under a strict decision policy:

- **0–500 ms**: acknowledge, answer simply, or ask a clarifying question
- **0.5–2 s**: optionally use one fast capability
- **>2 s expected work**: create a background job and keep the conversation moving

### Example behavior
User:
> Review my backend auth design and also search for best practices.

Jarvis:
> Got it. I’m sending the auth design to the dev reviewers and starting a best-practices search. While that runs, is this for customer accounts or internal staff?

This is the core interaction style SpecTalk should aim for.

---

## 3. Control Plane / Gateway

This is the most important system boundary in v1.

The Control Plane is the stable internal API between the voice layer and all execution systems.

### Responsibilities
- register capabilities
- classify requests by latency and execution mode
- create and track job IDs
- route work to the right subsystem
- normalize results from all tools and teams
- manage session state
- manage event delivery
- arbitrate questions from teams
- enforce approvals and policy
- expose live status to Jarvis

### Why it matters
Without a control plane, the voice assistant becomes tightly coupled to tools, workers, teams, and execution details. That increases latency, complexity, and brittleness.

With a control plane, Jarvis only needs to speak one language:
- submit request
- receive events
- retrieve latest state
- ask user questions
- narrate progress

---

# Capability Model

Every external capability should be normalized into one of the following types.

## 1. `sync_tool`
Used for very fast work that is safe to do inline.

Examples:
- fetch short memory snippet
- read current build status
- retrieve a known config value

## 2. `async_job`
Used for slow or long-running work.

Examples:
- web search
- video generation
- document analysis
- repository indexing
- build execution

## 3. `team_request`
Used when a specialist workflow is required.

Examples:
- dev team review
- architecture analysis
- security assessment
- implementation planning

## 4. `interactive_session`
Used for operations that open a persistent control channel.

Examples:
- IDE control
- local shell bridge
- OpenClaw session

## 5. `event_subscription`
Used when Jarvis needs live updates.

Examples:
- build progress
- agent wave completion
- blocker notifications
- approval requests

---

# Latency Classes

The Control Plane must classify every action by latency expectation.

## Fast
Expected under ~800 ms.
Safe to do inline in a live turn.

## Medium
Expected between ~1 and 4 seconds.
May be started inline, but Jarvis should usually acknowledge and continue speaking.

## Slow
Expected above ~4 seconds.
Must become a background job.

## Long-running
May take minutes.
Must always run asynchronously and emit progress events.

This classification is essential for keeping the voice UX natural.

---

# Event-Driven Interaction Model

Background systems should not speak directly to Jarvis. They should publish structured events.

### Example event types

```json
{
  "type": "job.created",
  "job_id": "job_001",
  "kind": "search",
  "summary": "Search auth best practices"
}
```

```json
{
  "type": "job.progress",
  "job_id": "job_001",
  "status": "running",
  "message": "Found 12 relevant sources, ranking them now"
}
```

```json
{
  "type": "team.question",
  "job_id": "job_002",
  "team": "dev_team",
  "priority": "high",
  "blocking": true,
  "question": "Should the MVP support multiple organizations?"
}
```

```json
{
  "type": "job.completed",
  "job_id": "job_001",
  "result_ref": "result://search/job_001"
}
```

```json
{
  "type": "approval.required",
  "job_id": "job_004",
  "action": "deploy_production",
  "reason": "This action affects live infrastructure"
}
```

Jarvis consumes these events and converts them into natural conversation.

---

# Specialist Team Model

For v1, use **one team**: `dev_team`.

Do not begin with a free-form multi-agent swarm.

Instead, implement the dev team as a structured pipeline with clear stages.

## Dev Team internal pipeline

1. **Planner**
   - interprets the user request
   - defines the objective
   - identifies likely workstreams

2. **Architecture Reviewer**
   - checks system design coherence
   - identifies coupling, scale, or maintainability issues

3. **Security Reviewer**
   - checks auth, data handling, trust boundaries, secrets, and exposure risks

4. **Frontend Reviewer**
   - assesses user-facing architecture and integration implications

5. **Backend Reviewer**
   - assesses APIs, data models, services, queues, and implementation constraints

6. **Question Synthesizer**
   - merges all missing information into the minimum blocking question set

7. **Spec Patcher**
   - updates the working spec incrementally

### Why this is better than a free swarm
- lower latency
- easier debugging
- more deterministic outputs
- easier observability
- easier token and cost control
- easier to convert into future specialized teams later

---

# Question Arbitration

Not every internal question should reach the user.

The Control Plane should include a **Question Arbiter** that ranks candidate questions by:

- whether the question is truly blocking
- whether the answer can be inferred
- whether the question can be deferred
- how much user effort it requires
- whether it unlocks multiple downstream tasks

### Policy
Jarvis should ask only:
- the minimum blocking question
- at the next natural pause in the conversation
- in natural language

### Example
Internal team output:
- Backend asks about tenancy
- Security asks about auth provider
- Frontend asks about mobile support

Question arbiter chooses:
> Before I lock the architecture, should this app support multiple organizations, or just a single team?

That single answer may resolve several downstream assumptions.

---

# Memory Model

SpecTalk needs at least three memory scopes.

## 1. Turn memory
Short-lived context for the current live interaction.

Examples:
- current question
- most recent answer
- latest active job references

## 2. Session memory
Persists for the current project/session.

Examples:
- project name
- chosen stack
- auth decisions
- open questions
- accepted tradeoffs

## 3. Project memory
Longer-lived knowledge that can be reused later.

Examples:
- preferred architecture style
- standard deployment target
- favored auth provider
- prior project constraints

Jarvis should read memory frequently, but write memory only from meaningful events:
- confirmed user decisions
- completed team findings
- explicit user preferences
- final spec choices

---

# Spec Formation Model

Your original idea of building the spec live is strong. Keep it.

But make the implementation incremental and structured.

## Recommended live artifacts

```text
/tmp/spectalk-session-{id}/
    transcript.log
    working-memory.json
    decision-register.json
    open-questions.json
    concerns.json
    active-jobs.json
    spec-draft.md
```

## Rules
- do not rewrite the whole spec every turn
- apply small patches based on confirmed user decisions or strong internal consensus
- keep unresolved assumptions explicitly marked
- attach provenance when useful

### Example decision entry

```json
{
  "decision_id": "dec_012",
  "topic": "authentication",
  "value": "Google Sign-In + email/password",
  "source": "user_confirmed",
  "timestamp": "..."
}
```

---

# Build and Execution Model

The build pipeline should remain in the private execution environment.

## Execution responsibilities
- run Spec Kit or equivalent planning flow
- split work into PR domains or workstreams
- generate task graph
- execute waves in parallel
- update status and blockers
- publish completion events

## Important v1 rule
Jarvis should remain available during execution.

Do not make Jarvis disappear during long-running builds.

Instead, Jarvis shifts into a lighter **progress concierge mode**.

It should still be able to answer things like:
- What is happening right now?
- Why is the backend blocked?
- Pause the build.
- Continue from the last successful wave.
- Skip logo generation.
- Ask the dev team whether Supabase is overkill.

This produces a much more natural product than silence during build.

---

# Recommended Service Split

You currently describe two services. That is acceptable for deployment, but architecturally it is better to think in three logical services.

## 1. Realtime Voice Service
Public-facing.

### Responsibilities
- transport handling
- audio provider integration
- session runtime
- barge-in / interruption handling
- turn manager
- low-latency response loop

## 2. Control Plane Service
Can be deployed with the voice service or separately.

### Responsibilities
- auth
- session state
- memory
- job management
- event bus
- capability registry
- team dispatch
- approvals
- normalized status APIs

## 3. Execution Plane Service
Private.

### Responsibilities
- dev team pipeline
- search workers
- build orchestrator
- media jobs
- OpenClaw / MCP bridges
- local/private execution

---

# Security and Trust Boundaries

## Public side
Only the realtime voice surface should be public-facing.

## Private side
The execution plane should remain private.

## Recommended trust boundaries
- public client connects only to realtime voice service
- realtime voice service authenticates the user
- control plane validates session and policy on each request
- execution plane is reachable only through private networking and service auth
- dangerous actions require explicit approval

## Approval-required examples
- file deletion
- shell execution with write effects
- deployment
- external messages
- purchases
- destructive edits

---

# Sequence Diagrams

## 1. Live Conversation with Background Search

```text
User → Voice Runtime: “Search best practices for OAuth in B2B SaaS”
Voice Runtime → Jarvis: completed user turn
Jarvis → Control Plane: create async search job
Control Plane → Search Worker: execute search
Control Plane → Event Bus: job.created
Jarvis → User: “I’m on it. While that runs, are you using Google Workspace, Microsoft, or both?”
Search Worker → Control Plane: partial results
Control Plane → Event Bus: job.progress
User → Jarvis: “Google first.”
Jarvis → Memory: store IdP preference
Search Worker → Control Plane: final ranked results
Control Plane → Event Bus: job.completed
Jarvis → User: “The search is ready. The strongest recommendation is to support Google OIDC first, then add SAML later if enterprise customers require it.”
```

## 2. Dev Team Review That Produces a Question

```text
User → Jarvis: “Design the backend for a project management app.”
Jarvis → Control Plane: create team_request(dev_team)
Control Plane → Dev Team: analyze request
Dev Team Planner → Reviewers: distribute structured task
Reviewers → Question Synthesizer: missing info and risks
Question Synthesizer → Control Plane: candidate questions + spec patches
Control Plane → Question Arbiter: rank questions
Question Arbiter → Jarvis: best blocking question
Jarvis → User: “One choice affects the backend shape: should this support multiple organizations, or just one internal team?”
```

## 3. Build Progress During Ongoing Conversation

```text
User → Jarvis: “Build it.”
Jarvis → Control Plane: create async build job
Control Plane → Orchestrator: start build
Orchestrator → Event Bus: build.started
Jarvis → User: “Starting the build now.”

User → Jarvis: “What is it doing?”
Jarvis → Control Plane: get latest build status
Control Plane → Jarvis: wave 1 running, backend scaffold complete, auth pending
Jarvis → User: “Backend scaffold is done. It’s working on auth now.”

Orchestrator → Event Bus: blocker detected
Control Plane → Jarvis: blocker summary
Jarvis → User: “I need one decision before it continues: do you want Supabase Auth or custom JWT auth?”
```

## 4. Approval Flow for a Sensitive Action

```text
User → Jarvis: “Deploy this to production.”
Jarvis → Control Plane: request deployment
Control Plane → Policy Engine: evaluate action
Policy Engine → Control Plane: approval required
Control Plane → Jarvis: approval.required event
Jarvis → User: “This will deploy to production. Do you want me to continue?”
User → Jarvis: “Yes, continue.”
Jarvis → Control Plane: approval granted
Control Plane → Execution Plane: execute deployment
```

---

# Folder Structure for v1

```text
spectalk/
├── realtime_runtime/
│   ├── transports/
│   │   ├── websocket.py
│   │   ├── webrtc.py
│   │   └── twilio.py
│   ├── providers/
│   │   ├── base.py
│   │   ├── nova_sonic.py
│   │   ├── gpt4o_realtime.py
│   │   ├── gemini_live.py
│   │   └── custom_pipeline.py
│   ├── audio/
│   │   ├── vad.py
│   │   ├── buffering.py
│   │   └── playback.py
│   ├── turn_manager.py
│   ├── interruption_manager.py
│   ├── session_runtime.py
│   └── main.py
│
├── conversation/
│   ├── jarvis.py
│   ├── intent_router.py
│   ├── response_policy.py
│   ├── question_manager.py
│   ├── progress_narrator.py
│   └── memory_adapter.py
│
├── control_plane/
│   ├── gateway.py
│   ├── capability_registry.py
│   ├── job_manager.py
│   ├── event_bus.py
│   ├── approval_policy.py
│   ├── question_arbiter.py
│   ├── team_dispatcher.py
│   ├── session_store.py
│   ├── status_api.py
│   └── state_models.py
│
├── teams/
│   └── dev_team/
│       ├── entrypoint.py
│       ├── planner.py
│       ├── architecture_review.py
│       ├── security_review.py
│       ├── frontend_review.py
│       ├── backend_review.py
│       ├── question_synthesizer.py
│       ├── spec_patcher.py
│       └── output_schema.py
│
├── execution_plane/
│   ├── orchestrator/
│   │   ├── daemon.py
│   │   ├── orchestrator.py
│   │   ├── pr_planner.py
│   │   ├── task_parser.py
│   │   ├── wave_executor.py
│   │   ├── spec_runner.py
│   │   └── status_writer.py
│   ├── workers/
│   │   ├── search_worker.py
│   │   ├── media_worker.py
│   │   └── repo_worker.py
│   └── bridges/
│       ├── mcp_bridge.py
│       └── openclaw_bridge.py
│
├── integrations/
│   ├── speckit/
│   ├── claude_code/
│   ├── auth/
│   └── telemetry/
│
├── shared/
│   ├── schemas/
│   │   ├── events.py
│   │   ├── jobs.py
│   │   ├── teams.py
│   │   └── memory.py
│   ├── logging/
│   └── utils/
│
├── tmp_sessions/
├── config.yaml
└── README.md
```

---

# Recommended Interfaces

## Jarvis → Control Plane

### `submit_request`
Creates a job, team request, or sync action.

Input:
- session_id
- user_id
- request_type
- payload
- latency_hint

### `get_status`
Returns normalized state for a job or session.

### `get_next_question`
Returns the highest-priority user-facing question, if any.

### `approve_action`
Confirms an approval-required operation.

### `subscribe_events`
Streams updates for a session or job.

---

## Dev Team output schema

```json
{
  "goal_understanding": "...",
  "assumptions": ["..."],
  "risks": ["..."],
  "conflicts": ["..."],
  "missing_information": ["..."],
  "recommended_question_for_user": "...",
  "proposed_spec_patches": [
    {
      "section": "authentication",
      "content": "..."
    }
  ],
  "confidence": 0.84
}
```

This output should be deterministic and easy to audit.

---

# Implementation Strategy for v1

## Step 1
Build the narrowest possible path:
- one transport
- one provider
- one Jarvis persona
- one control plane
- one dev team
- one orchestrator
- one async search worker
- one build status stream

## Step 2
Support these user flows only:
- conversational spec refinement
- background search while talking
- dev team review with returned questions
- background build with progress narration

## Step 3
Add approvals and OpenClaw/MCP bridge only after the event model is stable.

---

# Migration from Current Architecture

## Keep
- public voice service
- private orchestrator
- spec-first build pipeline
- status reporting
- specialist review concept
- pluggable provider strategy
- private network boundary

## Change
- move specialist agents behind a team runtime
- introduce a control plane / gateway as first-class infrastructure
- stop thinking of Jarvis as the direct executor
- keep Jarvis active during build
- formalize jobs, events, and question arbitration
- convert design council from visible internal chatter into structured deliberation

---

# Final v1 Positioning Statement

**SpecTalk v1 is a realtime conversational shell over an event-driven execution system.**

The user talks to one assistant.
That assistant stays responsive.
All slow work happens in the background.
Specialist reasoning remains internal.
The system returns only the most useful questions, the clearest progress updates, and the next actionable decisions.

That is the architecture most likely to feel natural in voice while still scaling into a serious multi-agent development platform.

