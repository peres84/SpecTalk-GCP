# SpecTalk Execution Specs

## Purpose

This file contains ordered prompts to generate implementation specs and PR structure for SpecTalk v1.

Use them in order.
Each prompt is scoped so a coding agent can produce a spec, plan, tasks, and PR breakdown for one area at a time.

---

## Global Rules For Every Spec

Apply these rules to every feature spec:

- Scope only for v1
- Google Cloud only
- One visible assistant
- Web app and Meta glasses companion app are the only client entrypoints
- Backend tools are private
- Coding execution happens only in an isolated GCP sandbox
- Every long-running action must emit status events
- Every failure must map to a blocker type and user-facing message
- Prefer simple APIs and deterministic contracts over flexible abstractions

---

## Spec 01: Voice Interaction Foundation

### Goal

Build the first end-to-end voice interaction path from client to backend.

### Prompt

```md
Create a v1 implementation spec for the SpecTalk voice interaction foundation.

Context:
- SpecTalk has two client entrypoints: a web frontend app and a Meta glasses companion app.
- Both clients must send live audio to a Google Cloud voice agent backend.
- The backend must manage one assistant persona only.
- The backend must support realtime transcript updates, assistant responses, and session state.
- The backend must keep the conversation responsive and must not execute long coding jobs directly in the realtime path.

Scope:
- Web app audio capture and streaming
- Meta glasses companion app audio forwarding
- GCP voice session backend
- Session lifecycle
- Transcript/status events

Deliverables:
- architecture and sequence flow
- API contracts
- websocket or streaming contract
- frontend states
- backend session states
- acceptance criteria
- PR breakdown

Constraints:
- GCP only
- one provider path for v1
- no coding sandbox logic in this spec
```

### Expected PR Structure

- PR 1: session contracts and event schema
- PR 2: web frontend audio capture and streaming UI
- PR 3: Meta glasses companion app streaming path
- PR 4: voice backend session runtime

---

## Spec 02: Control Plane And Job Model

### Goal

Create the backend layer that receives requests from the voice agent, creates jobs, and tracks state.

### Prompt

```md
Create a v1 implementation spec for the SpecTalk control plane and job model.

Context:
- The voice agent should answer quickly for simple requests.
- Any longer task must become a job handled outside the realtime voice path.
- The control plane is responsible for job creation, job state, tool routing, and event normalization.
- The control plane must serve as the only backend gateway between the voice agent and the orchestrator.

Scope:
- session-to-job handoff
- sync tool actions vs async jobs
- job schema
- status event schema
- blocker schema
- approval event schema
- storage model for sessions and jobs

Deliverables:
- entity definitions
- API endpoints
- event model
- state machine
- acceptance criteria
- PR breakdown

Constraints:
- keep the API narrow
- optimize for one user-facing assistant
- all status must be easy to narrate back to the user
```

### Expected PR Structure

- PR 1: job/state models
- PR 2: control plane API endpoints
- PR 3: event persistence and status query endpoints
- PR 4: blocker and approval handling

---

## Spec 03: Tool Layer And Tool Contracts

### Goal

Define how the agent accesses tools safely and consistently.

### Prompt

```md
Create a v1 implementation spec for the SpecTalk tool layer.

Context:
- The assistant needs access to backend tools.
- Tools must not be directly exposed to the client.
- One of the main tools is a Google Cloud coding sandbox.
- Other tools may include memory, search, repo lookup, and job status.
- The system must support permission profiles and approval-required actions.

Scope:
- tool registry
- tool contract format
- permission profiles
- approval-required actions
- request/response normalization
- tool failure normalization

Deliverables:
- tool interface specification
- permission matrix
- approval model
- sample tool payloads
- blocker mapping rules
- PR breakdown

Constraints:
- every tool must return structured output
- dangerous actions must be explicit
- optimize for the coding sandbox as the main long-running tool
```

### Expected PR Structure

- PR 1: tool registry and base interfaces
- PR 2: permission profiles and approval policy
- PR 3: base tools such as status and memory
- PR 4: coding sandbox tool adapter

---

## Spec 04: Google Cloud Coding Sandbox

### Goal

Define the isolated runner where code execution happens.

### Prompt

```md
Create a v1 implementation spec for the SpecTalk Google Cloud coding sandbox.

Context:
- Coding work must run in an isolated environment on Google Cloud.
- The runner must support repository access, file changes, command execution, tests, and persistent workspaces.
- The recommended v1 runner is one Compute Engine VM with one persistent disk.
- The runner is private and must never be directly exposed to public clients.

Scope:
- Compute Engine runner design
- persistent workspace layout
- startup and shutdown flow
- secret injection
- repo access
- runtime environment for coding tasks
- idle reuse of the same VM

Deliverables:
- runner architecture
- workspace contract
- auth and secret flow
- repo lifecycle
- runner lifecycle
- acceptance criteria
- PR breakdown

Constraints:
- GCP only
- no interactive human login required inside the runner
- secrets never persist in workspace files
```

### Expected PR Structure

- PR 1: VM bootstrap and runner service
- PR 2: persistent workspace and metadata registry
- PR 3: secret injection and repo auth
- PR 4: sandbox execution wrapper and status emission

---

## Spec 05: Orchestrator And Execution Lifecycle

### Goal

Define the service that receives coding jobs, runs them in the sandbox, and reports progress or blockers.

### Prompt

```md
Create a v1 implementation spec for the SpecTalk orchestrator and execution lifecycle.

Context:
- The orchestrator receives coding jobs from the control plane.
- It prepares the workspace, launches execution in the GCP sandbox, tracks lifecycle state, and reports progress.
- It must convert raw failures into structured blocker types the assistant can explain to the user.
- It must support pause, resume, fail, and completion events.

Scope:
- job intake API
- runner assignment
- execution lifecycle states
- progress events
- blocker classification
- retry and resume model
- final result payload

Deliverables:
- orchestrator API
- state machine
- runner coordination protocol
- blocker taxonomy
- result schema
- acceptance criteria
- PR breakdown

Constraints:
- optimize for clarity over multi-runner complexity
- begin with one runner target
- user-facing messages must come from normalized blocker/status events
```

### Expected PR Structure

- PR 1: orchestrator job API and schemas
- PR 2: runner assignment and execution lifecycle
- PR 3: blocker classification and resume flow
- PR 4: final result summaries and audit trail

---

## Spec 06: User Control Panels

### Goal

Build the UI surfaces that show conversation state, job status, blockers, and controls.

### Prompt

```md
Create a v1 implementation spec for the SpecTalk user control panels.

Context:
- The user needs to see what the system is doing during and after voice interaction.
- The first UI can live in the web frontend.
- The panel should show transcript, current state, active jobs, recent events, blockers, and next actions.
- The panel should also let the user resolve blockers such as missing keys or approval requests.

Scope:
- session view
- transcript view
- job status panel
- blocker resolution UI
- approval UI
- result summary UI

Deliverables:
- information architecture
- state model
- API dependencies
- UX states
- acceptance criteria
- PR breakdown

Constraints:
- prioritize clarity and debugging value
- do not overdesign admin features in v1
- focus on one user project flow first
```

### Expected PR Structure

- PR 1: status panel and event feed
- PR 2: blocker and approval resolution UI
- PR 3: result summary and job history

---

## Spec 07: End-To-End Integration

### Goal

Connect the voice path, control plane, tool layer, sandbox, and orchestrator into one working flow.

### Prompt

```md
Create a v1 integration spec for the full SpecTalk execution flow.

Context:
- The system should let a user speak from the client, create a coding job, run it in the GCP sandbox, and receive progress or blocker updates back in the UI and voice assistant.
- All previous specs should connect through stable contracts.

Scope:
- end-to-end request flow
- event propagation
- error propagation
- blocker resolution path
- happy path demo flow
- observability requirements

Deliverables:
- sequence diagrams
- integration contracts
- end-to-end acceptance tests
- rollout plan
- PR breakdown

Constraints:
- one happy path first
- one blocker path first
- optimize for demo reliability
```

### Expected PR Structure

- PR 1: event wiring across services
- PR 2: end-to-end happy path
- PR 3: blocker and recovery path
- PR 4: observability and demo hardening

---

## Recommended Execution Order

Run the specs in this order:

1. Spec 01: Voice Interaction Foundation
2. Spec 02: Control Plane And Job Model
3. Spec 03: Tool Layer And Tool Contracts
4. Spec 04: Google Cloud Coding Sandbox
5. Spec 05: Orchestrator And Execution Lifecycle
6. Spec 06: User Control Panels
7. Spec 07: End-To-End Integration

---

## Final Notes

If a coding agent starts generating large alternative architectures, pull it back to these rules:

- simplify
- pick one path
- define contracts early
- keep blockers explicit
- optimize for a working v1 demo

