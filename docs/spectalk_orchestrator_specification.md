# SpecTalk Orchestrator Specification

## Purpose

This document defines the **SpecTalk Orchestrator**: the system that receives structured development requests from the control plane, prepares execution jobs, routes them to the appropriate runner, manages job state, handles blockers and approvals, and returns progress and results back to the rest of the platform.

The orchestrator is the bridge between:

- the **conversation system** (Jarvis)
- the **control plane**
- the **dev team outputs**
- the **isolated coding runners**
- the **repository provider**
- the **tooling and capability layer**

The orchestrator does **not** speak directly to the user. It is an execution coordination system.

---

# 1. Role in the Overall Architecture

## Position in SpecTalk

```text
User
  ↓
Jarvis Conversation Engine
  ↓
Control Plane / Gateway
  ↓
Orchestrator
  ↓
Runner Selector + Job Manager
  ↓
Portable Runner (local PC / VPS / cloud VM)
  ↓
Claude Code or Codex + MCP/skills + repo + shell
```

## Primary responsibility
The orchestrator converts a high-level approved development request into a controlled, observable execution lifecycle.

## Non-goals
The orchestrator should not:
- manage low-latency voice behavior
- speak directly to the user
- own long-term conversational memory
- directly expose shell access to public clients
- embed provider-specific UX logic in its public contract

---

# 2. Design Goals

## Primary goals
- route execution jobs to a valid runner
- support local, VPS, and cloud runner targets
- keep execution isolated from the public voice system
- allow pause/resume/blocking workflows
- support both Claude Code and Codex
- provide deterministic status and auditability
- normalize runner behavior behind one orchestration contract

## Secondary goals
- enable future multi-runner scheduling
- support project/workspace affinity
- allow cost-aware host selection
- support approvals and secret-request workflows

---

# 3. Core Principles

## 3.1 Portable runners
The orchestrator must treat execution environments as interchangeable workers that follow a common protocol.

## 3.2 Externalized policy
Permissions, approvals, and capability exposure must be controlled by orchestration policy, not hardcoded in the runner.

## 3.3 Persistent workspaces, not persistent shells
The orchestrator should reason about durable workspaces and ephemeral execution sessions.

## 3.4 Structured status, not raw terminal output
The orchestrator communicates via structured events and state transitions.

## 3.5 User interactions happen through the control plane
Any need for clarification, approval, or secret entry must flow back through the control plane and Jarvis.

---

# 4. Responsibilities

The orchestrator is responsible for the following functions.

## 4.1 Job intake
- receive approved coding job requests from the control plane
- validate schema and policy compatibility
- assign or confirm `job_id`
- determine execution requirements

## 4.2 Execution planning
- determine provider (`claude_code`, `codex`, or fallback policy)
- determine runner capability requirements
- determine workspace requirements
- determine permission profile
- determine secret requirements

## 4.3 Runner selection
- select a compatible runner
- prefer workspace affinity when possible
- enforce repo and capability constraints
- apply cost or locality preferences

## 4.4 Workspace coordination
- bind the job to a workspace
- ensure repo checkout/branch strategy is valid
- ensure the runner can access or mount the workspace

## 4.5 Job lifecycle management
- start job
- track state transitions
- process progress and blocker events
- handle pause, resume, cancel, retry

## 4.6 Approval and blocker routing
- convert runner blockers into structured orchestration states
- notify control plane when user input is required
- resume execution after resolution

## 4.7 Result handling
- collect execution summary
- collect commit hashes / branch / PR links or references
- publish final status to control plane

## 4.8 Auditing
- record key events, decisions, approvals, and outcomes

---

# 5. Core Entities

## 5.1 Job
A job is a single unit of coding execution.

### Example
```json
{
  "job_id": "job_123",
  "project_id": "proj_001",
  "workspace_id": "ws_001",
  "kind": "coding",
  "provider": "claude_code",
  "state": "queued"
}
```

## 5.2 Workspace
A workspace is the persistent execution context for a project/repo.

### Properties
- repo identity
- filesystem root
- active/default branch info
- tool caches
- metadata
- last known runner affinity

## 5.3 Runner
A runner is a portable execution worker installed on a local PC, VPS, or cloud VM.

### Properties
- runner ID
- host type
- capability set
- online/offline state
- workspace capacity
- supported providers
- permissions support

## 5.4 Capability manifest
A capability manifest declares which tools, MCP servers, skills, and integrations are enabled for a specific job.

## 5.5 Permission profile
A permission profile defines the execution scope allowed for a job.

## 5.6 Blocker
A blocker is a structured reason a job cannot continue without intervention.

---

# 6. Supported Deployment Modes

The orchestrator must support multiple runner deployment targets.

## 6.1 Local runner mode
Runner installed on the user’s personal machine.

### Best for
- low-cost personal use
- local repo access
- rapid iteration

### Risks
- machine availability
- weaker isolation than cloud or VPS

## 6.2 VPS runner mode
Runner installed on a private VPS.

### Best for
- remote persistent availability
- more isolation than a personal laptop
- simple hosted setup

## 6.3 Cloud VM runner mode
Runner installed on a managed cloud VM.

### Best for
- stronger isolation
- cloud-native auth
- scalable future path

## 6.4 Future: cluster mode
Multiple runners with scheduling across shared infrastructure.

---

# 7. Job Intake Contract

The orchestrator accepts a `coding_job_request` from the control plane.

## Required fields

```json
{
  "job_id": "job_123",
  "project_id": "proj_001",
  "workspace_id": "ws_001",
  "requested_provider": "claude_code",
  "repo": {
    "host": "github",
    "owner": "acme",
    "name": "payments-api",
    "default_branch": "main"
  },
  "branch": {
    "mode": "create",
    "name": "spectalk/job-123-auth-module"
  },
  "task": {
    "title": "Implement authentication scaffolding",
    "goal": "Build backend auth foundation based on approved spec",
    "instructions_ref": "/specs/job-123.md",
    "artifacts": [
      "/specs/auth.md",
      "/tasks/backend-auth.md"
    ]
  },
  "permissions_profile": "dangerous_repo_write",
  "capability_policy": {
    "mcp_servers": ["github", "filesystem", "postgres-readonly"],
    "skills": ["repo-conventions", "backend-review", "test-strategy"]
  },
  "post_actions": {
    "commit": true,
    "push": true,
    "open_pr": false
  }
}
```

## Intake validation rules
The orchestrator must reject requests that:
- specify an unsupported provider
- request a capability not allowed by policy
- target a repo host not configured
- require a permission profile not allowed for the user/project
- omit required workspace or repo metadata

---

# 8. Runner Registration Specification

A runner must register itself with the orchestrator or runner registry.

## Registration payload

```json
{
  "runner_id": "runner_local_01",
  "host_type": "local",
  "hostname": "mbp-dev",
  "status": "online",
  "supports": {
    "providers": ["claude_code", "codex"],
    "dangerous_mode": true,
    "git_push": true,
    "docker": false,
    "local_mcp": true
  },
  "workspace_root": "/Users/me/spectalk-workspaces",
  "capacity": {
    "max_concurrent_jobs": 1,
    "active_jobs": 0
  },
  "repo_hosts": ["github"],
  "labels": ["personal", "low-cost", "preferred-for-proj_001"]
}
```

## Heartbeat requirements
The runner must periodically send:
- online state
- capacity usage
- current job count
- optional health summary

## Offline behavior
If heartbeats stop:
- runner becomes `suspected_offline`
- active jobs move to `runner_unreachable`
- orchestrator decides whether to wait, fail, or recover

---

# 9. Runner Selection Policy

The orchestrator selects a runner using the following decision order.

## 9.1 Hard filters
A runner is eligible only if it:
- is online
- supports the requested provider
- supports the permission profile
- supports required repo host access
- supports required capability classes
- has capacity

## 9.2 Preference order
Among eligible runners, prefer:
1. runner already associated with the workspace
2. runner with local workspace affinity
3. runner with lower estimated cost
4. runner with lower load
5. runner matching project/user preferences

## 9.3 Failure behavior
If no runner is available:
- keep job in `waiting_for_runner`
- notify control plane
- optionally suggest alternate runner targets

---

# 10. Workspace Specification

A workspace is the persistent execution root for a project.

## Required properties
- `workspace_id`
- `project_id`
- `repo_host`
- `repo_owner`
- `repo_name`
- `default_branch`
- `path`
- `status`

## Recommended filesystem layout

```text
/workspace/
  repo/
  specs/
  tasks/
  artifacts/
  logs/
  cache/
  job_state/
  metadata.json
```

## Metadata example

```json
{
  "workspace_id": "ws_001",
  "project_id": "proj_001",
  "repo": "github/acme/payments-api",
  "default_branch": "main",
  "active_branch": "spectalk/job-123-auth-module",
  "last_runner_id": "runner_local_01",
  "last_job_id": "job_123"
}
```

## Workspace rules
- workspaces must persist code and metadata
- workspaces must not persist live secrets
- workspaces must store enough state for recovery or retry

---

# 11. Capability Manifest Specification

The orchestrator must generate a job-specific capability manifest.

## Purpose
Control which MCP servers, skills, tools, and prompts are exposed to the coding agent.

## Example

```json
{
  "job_id": "job_123",
  "provider": "claude_code",
  "allowed_mcp_servers": [
    "github",
    "filesystem",
    "postgres-readonly"
  ],
  "allowed_skills": [
    "repo-conventions",
    "backend-review",
    "test-strategy"
  ],
  "context_refs": [
    "/workspace/specs/auth.md",
    "/workspace/specs/decision-register.json"
  ],
  "tool_constraints": {
    "network_access": true,
    "filesystem_scope": "/workspace/repo"
  }
}
```

## Rules
- base MCP runtime may be preinstalled in the image
- actual server activation is controlled per job
- credentials for MCP servers are injected at runtime only
- skills may be prebundled but must be enabled by manifest

---

# 12. Permission Profiles

The orchestrator must treat permissions as explicit profiles.

## Example profiles

### `read_only_analysis`
Allows:
- read repo
- inspect files
- run safe read-only commands

### `repo_write`
Allows:
- modify files
- run local project commands
- create commits

### `dangerous_repo_write`
Allows:
- broad repo modification
- dependency installation
- build/test commands
- commits
- push if policy allows

### `deploy_capable`
Allows:
- repo operations
- deployment workflow after approval

## Policy enforcement
The runner receives the active profile and must refuse out-of-scope actions.
The orchestrator must enforce approval gates for profile-sensitive actions.

---

# 13. Authentication and Secret Responsibilities

The orchestrator does not permanently store active execution secrets in workspaces.

## 13.1 Provider auth responsibilities
The orchestrator or control plane must prepare provider auth inputs.

### Claude Code
- preferred auth path: Vertex AI / service account-based cloud auth
- alternate path: provider key injected at runtime

### Codex
- OpenAI API key injected at runtime

## 13.2 Git auth responsibilities
Preferred approach:
- GitHub App installation token minted per job or per action

Alternate approaches:
- deploy key
- scoped automation token

## 13.3 Secret delivery model
The runner should receive:
- secret references
- or short-lived action-specific tokens

The orchestrator should avoid sending broad permanent credentials where possible.

---

# 14. Job State Machine

The orchestrator must track all jobs using an explicit state machine.

## States
- `queued`
- `waiting_for_runner`
- `assigned`
- `preparing_workspace`
- `authenticating`
- `preparing_capabilities`
- `running_agent`
- `testing`
- `committing`
- `pushing`
- `waiting_for_user_input`
- `waiting_for_secret`
- `waiting_for_approval`
- `waiting_for_external_system`
- `paused`
- `completed`
- `failed`
- `cancelled`
- `runner_unreachable`

## State transition rules
A job may only transition through allowed edges.

### Examples
- `queued` → `waiting_for_runner`
- `waiting_for_runner` → `assigned`
- `assigned` → `preparing_workspace`
- `running_agent` → `testing`
- `running_agent` → `waiting_for_user_input`
- `pushing` → `completed`
- `any active state` → `failed`
- `any active state` → `cancelled`

---

# 15. Runner Execution Protocol

The runner follows a controlled lifecycle.

## 15.1 Startup sequence
1. runner boots
2. loads local config
3. registers with orchestrator
4. starts heartbeat
5. advertises capacity and capabilities

## 15.2 Job execution sequence
1. receive job assignment
2. acknowledge assignment
3. prepare workspace
4. fetch runtime auth material
5. load capability manifest
6. prepare repo and branch
7. execute Claude Code or Codex task
8. emit progress states
9. emit blocker events when necessary
10. run validation/test phase if configured
11. commit if configured
12. push if configured and authorized
13. emit completion summary

## 15.3 Completion summary

```json
{
  "job_id": "job_123",
  "result": "completed",
  "summary": "Authentication scaffolding implemented and tests passed",
  "commit_hashes": ["abc123"],
  "branch": "spectalk/job-123-auth-module",
  "artifacts": [
    "/workspace/logs/job_123.log",
    "/workspace/artifacts/job_123-summary.md"
  ]
}
```

---

# 16. Blocker and User-Input Protocol

The orchestrator must support structured blockers when the coding agent cannot continue.

## Blocker types
- `missing_user_input`
- `missing_secret`
- `approval_required`
- `external_dependency_unavailable`
- `provider_failure`
- `repo_access_issue`
- `workspace_conflict`

## Example blocker event

```json
{
  "job_id": "job_123",
  "type": "missing_secret",
  "state": "waiting_for_secret",
  "message": "Stripe staging key is required to complete billing integration tests",
  "resolution_options": [
    "provide_secret",
    "skip_live_integration",
    "use_mock_provider"
  ]
}
```

## Orchestrator behavior
When a blocker occurs:
1. job state is updated
2. blocker record is persisted
3. control plane is notified
4. control plane decides how to ask the user or resolve automatically
5. orchestrator waits for resolution payload
6. job resumes or fails

---

# 17. Approval Specification

The orchestrator must support approval-gated actions.

## Approval triggers
Examples include:
- pushing to a protected target
- modifying infrastructure or deployment config
- opening PRs in external repos
- using high-sensitivity secrets
- deployment actions

## Approval request payload

```json
{
  "approval_id": "apr_001",
  "job_id": "job_123",
  "action": "push_branch",
  "reason": "Project policy requires approval before remote push",
  "details": {
    "branch": "spectalk/job-123-auth-module"
  }
}
```

## Approval resolution
Allowed outcomes:
- approved
- denied
- modified scope

---

# 18. Status Event Contract

The orchestrator must emit normalized status events to the control plane.

## Event types
- `job.created`
- `job.assigned`
- `job.state_changed`
- `job.progress`
- `job.blocked`
- `job.approval_requested`
- `job.resumed`
- `job.completed`
- `job.failed`
- `job.cancelled`

## Example state event

```json
{
  "type": "job.state_changed",
  "job_id": "job_123",
  "state": "running_agent",
  "message": "Claude Code is implementing backend authentication scaffolding",
  "timestamp": "..."
}
```

## UX requirement
These events must be simple enough for Jarvis to convert into natural spoken updates.

---

# 19. Failure Handling and Recovery

The orchestrator must classify failures explicitly.

## Failure classes
- runner unavailable
- provider authentication failure
- git authentication failure
- repository conflict
- workspace corruption
- missing required secret
- approval denied
- provider execution failure
- policy violation

## Recovery policy
Each failure class should define:
- retry allowed or not
- automatic retry count
- whether user intervention is required
- whether a new runner may be selected

## Resume requirements
To support resume, the orchestrator must persist:
- last successful state
- current blocker if any
- workspace reference
- runner reference
- execution metadata

---

# 20. Repository Workflow Policy

The orchestrator must enforce predictable git behavior.

## Default job flow
1. fetch repo
2. sync default branch
3. create or checkout target branch
4. execute coding instructions
5. run validation
6. stage changes
7. commit changes
8. push branch
9. optionally open/update PR

## Commit identity
Runner should set a deterministic identity, for example:
- `SpecTalk Bot`
- `bot@yourdomain.com`

## Commit metadata
Optionally include:
- job ID
- project ID
- execution provider

---

# 21. Public Orchestrator API

The orchestrator’s external API should remain narrow.

## 21.1 Submit job
`POST /jobs`

Creates a coding job.

## 21.2 Get job
`GET /jobs/{job_id}`

Returns full job state.

## 21.3 List job events
`GET /jobs/{job_id}/events`

Returns event timeline.

## 21.4 Resolve blocker
`POST /jobs/{job_id}/resolve-blocker`

Used by control plane after user answer, secret provision, or external resolution.

## 21.5 Approve action
`POST /jobs/{job_id}/approve`

Resolves an approval gate.

## 21.6 Pause job
`POST /jobs/{job_id}/pause`

## 21.7 Resume job
`POST /jobs/{job_id}/resume`

## 21.8 Cancel job
`POST /jobs/{job_id}/cancel`

---

# 22. Runner-Facing API

The runner needs a private API contract.

## 22.1 Register
`POST /runners/register`

## 22.2 Heartbeat
`POST /runners/{runner_id}/heartbeat`

## 22.3 Poll for assignment
`POST /runners/{runner_id}/poll`

Preferred v1 model: pull-based assignment.

## 22.4 Update job state
`POST /jobs/{job_id}/state`

## 22.5 Emit progress
`POST /jobs/{job_id}/progress`

## 22.6 Emit blocker
`POST /jobs/{job_id}/blocker`

## 22.7 Complete job
`POST /jobs/{job_id}/complete`

## 22.8 Fail job
`POST /jobs/{job_id}/fail`

---

# 23. Scheduling Policy

The orchestrator should support simple scheduling in v1.

## Inputs to scheduling
- required provider
- repo host access
- workspace affinity
- current load
- host type preference
- cost preference

## Suggested host preference options
- `prefer_local`
- `prefer_vps`
- `prefer_cloud`
- `lowest_cost`
- `highest_isolation`

## Example rule
A personal user may configure:
- prefer local runner when online
- fallback to VPS runner
- never use managed cloud

---

# 24. Configuration Model

The orchestrator should be configurable at global, project, and user levels.

## Global config examples
- enabled repo hosts
- enabled providers
- default permission profiles
- default host preferences
- retry policies

## Project config examples
- preferred runner labels
- default branch naming format
- push approval policy
- allowed MCP servers
- allowed skill packs

## User config examples
- default runner target
- approval strictness
- preferred provider

---

# 25. Security Requirements

## Required rules
- orchestrator endpoints must not be public without auth
- runner APIs must be private/authenticated
- secrets must not be stored in persistent workspace files
- approvals must be logged
- repo write actions must be attributable to a job
- capability exposure must be explicit per job

## Recommended controls
- mutual auth or signed runner tokens
- project-level repo allowlists
- restricted secret scopes
- runner identity verification
- audit logs for push and approval events

---

# 26. Observability Requirements

The orchestrator must log:
- job creation
- runner assignment
- state transitions
- blocker creation/resolution
- approval requests/resolutions
- completion summaries
- failure classes

The orchestrator should expose metrics for:
- jobs by state
- runner availability
- average assignment time
- average execution time
- failure rates by class
- approval frequency
- blocker frequency

---

# 27. Minimal v1 Scope

To keep implementation realistic, v1 should support:
- one repo host (GitHub)
- two providers (Claude Code, Codex)
- one pull-based runner protocol
- one active job per runner
- one workspace per project
- one approval flow
- one blocker resolution flow
- local and VPS runner deployment modes
- cloud VM mode optionally behind a feature flag

Do not begin with:
- multi-provider fallback chains per step
- many concurrent jobs per runner
- many repo hosts
- deep cluster scheduling
- complex autoscaling

---

# 28. Recommended Build Order

## Stage 1 — contracts
1. define job schema
2. define runner schema
3. define state machine
4. define blocker and approval schemas

## Stage 2 — core service
5. implement orchestrator job API
6. implement runner registration and heartbeat
7. implement pull-based assignment
8. implement state/event persistence

## Stage 3 — execution integration
9. implement workspace binding
10. implement capability manifest generation
11. implement provider launch hooks for Claude Code and Codex
12. implement git workflow integration

## Stage 4 — control and recovery
13. implement blocker resolution
14. implement approval flow
15. implement retry/resume policy
16. implement failure classification

## Stage 5 — deployment targets
17. support local runner install
18. support VPS runner install
19. support cloud VM runner install
20. add cost-aware selection

---

# 29. Final Positioning

The SpecTalk Orchestrator is a **portable execution coordinator** for coding jobs.

It is not a voice agent.
It is not a raw shell wrapper.
It is not tied to one host environment.

Its role is to take approved development work and ensure that it runs:
- on the right runner
- with the right capabilities
- with the right permissions
- with the right repo and workspace context
- with full observability
- and with a structured path back to the user whenever execution gets blocked

This is the component that makes SpecTalk’s “talk, plan, build, push” workflow operationally reliable.

