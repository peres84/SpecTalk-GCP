# SpecTalk GCP Coding Execution Plan

## Purpose

This document explains, phase by phase, how to build the isolated coding execution layer for **SpecTalk** on **Google Cloud**.

This part of the system is critical because it is the bridge between:

- the **voice and design experience**
- the **dev team output**
- the **actual code generation and repository updates**

The goal is to make sure that once the dev team finishes shaping a PR request or implementation plan, SpecTalk can reliably hand that work to an isolated coding environment where **Claude Code** or **Codex** can:

- read the request
- access the repository
- create or modify code
- run commands with broad permissions when needed
- commit changes
- push to git
- optionally open or update a pull request

This must work **without repeated manual setup** whenever the environment restarts.

---

# Core Principle

The right mental model is:

> **persistent workspace + ephemeral compute + externalized credentials**

That means:

- the coding environment may stop and start
- the tools should already be installed in the base image
- credentials should never depend on interactive login inside the runner
- the repository and local state should survive restarts
- authentication should be injected at runtime

This is the foundation for a reliable automated coding system.

---

# What This System Must Solve

Before implementation, it is useful to state the exact problems this layer must solve.

## Problem 1 — isolated execution
Claude Code or Codex may run commands with broad filesystem and shell permissions. This must happen in an isolated environment, never in the public voice runtime.

## Problem 2 — durable authentication
If the environment is recreated, the system should not require a human to log in again to Claude, OpenAI, GitHub, or Git.

## Problem 3 — durable repository state
The environment should be able to stop and resume without losing the checked-out repository, branch state, caches, and metadata.

## Problem 4 — controlled permissions
The coding agent should have enough power to do real work, but the system still needs guardrails around dangerous actions such as pushing, deleting files, or deploying.

## Problem 5 — orchestration compatibility
The coding layer must fit into the broader SpecTalk architecture:
- user speaks to Jarvis
- dev team refines request
- control plane creates coding job
- isolated runner executes it
- status flows back to Jarvis

---

# Recommended GCP Direction

For SpecTalk v1 on Google Cloud, the recommended architecture is:

## Preferred v1 runtime
- **Cloud Workstations** for isolated coding environments
- **persistent home directory** for workspace durability
- **custom workstation image** with tools preinstalled
- **Google service account** attached to workstation runtime
- **Secret Manager** for non-Google secrets
- **Claude Code via Vertex AI authentication**
- **Codex via OpenAI API key**
- **GitHub App** for git clone/push over HTTPS

## Simpler alternative
If Cloud Workstations feel too heavy operationally at first, use:
- **Compute Engine runner VMs**
- attached **persistent disks**
- same auth and secret model

Both models are valid. Cloud Workstations are usually cleaner for a dedicated isolated development workspace model. Compute Engine is simpler to reason about operationally.

---

# Phase 0 — define the contract between design and execution

Before touching infrastructure, define the handoff contract from the dev team to the coding runner.

This is the first phase because every later system depends on this contract.

## Goal
Create a structured payload that the runner can execute without ambiguity.

## Deliverable
Define a `coding_job_request` schema.

## Recommended fields

```json
{
  "job_id": "job_123",
  "project_id": "proj_001",
  "workspace_id": "ws_001",
  "provider": "claude_code",
  "repo": {
    "host": "github",
    "owner": "my-org",
    "name": "my-repo",
    "default_branch": "main"
  },
  "branch": {
    "mode": "create",
    "name": "feature/auth-setup"
  },
  "task": {
    "title": "Implement authentication scaffolding",
    "goal": "Create backend auth module and basic login flow",
    "instructions": "Use the PR design produced by the dev team",
    "artifacts": [
      "spec/auth.md",
      "tasks/backend-auth.md"
    ]
  },
  "permissions_profile": "dangerous_repo_write",
  "post_actions": {
    "commit": true,
    "push": true,
    "open_pr": false
  }
}
```

## Why this matters
If this contract is weak, the runner becomes hard to debug and hard to secure.

## What to finalize in this phase
- what the dev team outputs
- what the orchestrator sends to the runner
- what the runner must report back
- what counts as success, failure, partial success, or blocked

---

# Phase 1 — define security boundaries and trust model

This phase defines what is public, what is private, and who can access what.

## Goal
Protect the coding runner from becoming a loosely controlled remote shell.

## Core trust boundaries

### Public zone
- voice transports
- user-facing APIs
- session entrypoints

### private control zone
- control plane
- job manager
- token minting logic
- event bus
- status APIs

### private execution zone
- coding runners
- repo workspaces
- shell execution
- agent tools
- build tools
- optional deployment tools

## Required rule
The public voice runtime must never directly expose shell or git access.

## Key decisions in this phase
- how runners are network-isolated
- how the control plane authenticates to the runner layer
- which actions require approval
- whether runners may access the internet freely or through controlled egress

## Output of this phase
A written trust model answering:
- who can start a job
- who can inject secrets
- who can push code
- who can request deployment
- who can access persistent workspaces

---

# Phase 2 — choose the runner substrate

This phase decides where Claude Code or Codex will actually run.

## Goal
Select the isolated execution environment for v1.

## Option A — Cloud Workstations
Recommended if you want persistent workspace behavior with a strong development-environment model.

### Why choose it
- built around developer workspaces
- persistent home directory
- custom images supported
- strong fit for project-based isolated work
- good if you want pause/resume behavior

### Best use in SpecTalk
- one workstation per project
- or one workstation per active workspace

## Option B — Compute Engine VM runner pool
Recommended if you want the simplest first implementation.

### Why choose it
- easy to understand
- easy to control
- easy to debug
- very flexible for shell-heavy coding agents

### Best use in SpecTalk
- one VM image template
- one attached persistent disk per workspace
- one job executor daemon per runner

## Decision advice
If your top priority is **correctness and clarity**, Compute Engine may be the easiest first step.
If your top priority is **persistent isolated workspace UX**, Cloud Workstations are very attractive.

## Output of this phase
Choose one substrate for v1.
Do not try to support both initially.

---

# Phase 3 — create the persistent workspace model

This phase defines how repository state survives restarts.

## Goal
Ensure that when compute is stopped or replaced, the working repository still exists.

## Workspace contents
A workspace should persist:
- git repository
- branch state
- uncommitted recovery files if desired
- build cache
- package manager cache when useful
- runner metadata
- logs
- patch or transcript artifacts

## Workspace metadata example

```json
{
  "workspace_id": "ws_001",
  "project_id": "proj_001",
  "repo": "github.com/my-org/my-repo",
  "default_branch": "main",
  "active_branch": "feature/auth-setup",
  "provider": "claude_code",
  "last_job_id": "job_123",
  "status": "idle"
}
```

## Recommended structure

```text
/workspace/
  repo/
  logs/
  job_state/
  artifacts/
  cache/
  metadata.json
```

## Important rule
Do not persist secrets in the workspace.
Persist work, not credentials.

## Output of this phase
A workspace directory contract and workspace lifecycle policy.

---

# Phase 4 — build the base runner image

This phase creates the image used by all coding environments.

## Goal
Make every runner start from a ready-to-execute environment.

## What should be preinstalled
- Claude Code CLI
- Codex CLI
- git
- GitHub CLI if useful
- Node, Python, and any required language runtimes
- build tools relevant to your projects
- your runner bootstrap script
- your status reporter
- your job executor daemon

## Why this matters
If the environment needs to install core tools every time, startup becomes slow and fragile.

## Recommended image layers

### Layer 1 — OS and shell tools
- base Linux image
- bash, curl, jq, ca-certificates, git

### Layer 2 — developer runtimes
- node
- npm / pnpm / yarn if needed
- python
- uv / pip if needed
- docker client only if truly necessary

### Layer 3 — coding CLIs
- Claude Code
- Codex CLI

### Layer 4 — SpecTalk runner code
- bootstrap script
- secret fetcher
- workspace initializer
- job executor
- status emitter

## Output of this phase
A versioned base image that can be reused across all runners.

---

# Phase 5 — define authentication for Claude Code and Codex

This phase solves model-provider authentication without repeated human setup.

## Goal
Make model access fully service-driven.

## Claude Code strategy
Use **Vertex AI authentication**.

### Why
This avoids depending on an interactive Claude account login inside the runner.

### Required model
- runner has Google Cloud credentials through its attached service account
- environment variables configure Claude Code to use Vertex AI
- no repeated browser or subscription login is needed inside the workspace

## Codex strategy
Use **OpenAI API key authentication**.

### Why
Codex is best used programmatically through API key auth in automated environments.

### Required model
- key stored in Secret Manager
- key fetched at runtime
- injected into environment only for job execution

## Important rule
Never depend on interactive login as the primary auth path inside the runner.

## Output of this phase
A documented provider auth flow for both Claude Code and Codex.

---

# Phase 6 — define Git authentication properly

This phase is extremely important because pushing code is part of the product promise.

## Goal
Let runners clone, commit, and push securely without using a human developer’s password or fragile personal credentials.

## Recommended separation
Separate:
- **git authentication**
- **git commit identity**

## Part A — git authentication
This controls whether the runner is allowed to access the repository.

### Preferred solution — GitHub App
Use a GitHub App for platform-grade repository automation.

### Why this is best
- access belongs to the app, not a human
- short-lived installation tokens can be minted per job
- can be scoped to specific repos or organizations
- easier audit and revocation
- much better fit for SpecTalk than a personal SSH key

### How it works
- GitHub App private key is stored in Secret Manager
- control plane mints a short-lived installation token when a job starts
- runner receives token or fetches it from a secure internal endpoint
- runner clones and pushes over HTTPS

## Alternative for v1 — deploy key
Use only if you want a very simple single-repo model.

### Limits
- awkward across many repos
- weaker long-term governance
- less flexible than GitHub App tokens

## Avoid
- email/password auth
- human personal SSH keys as core platform architecture
- long-lived personal PATs unless temporarily bootstrapping

## Part B — git commit identity
This is only the author/committer metadata.

The runner can set:

```bash
git config user.name "SpecTalk Bot"
git config user.email "bot@yourdomain.com"
```

This does not grant repo access. It only determines how commits appear.

## Output of this phase
A complete Git strategy:
- GitHub App for repo auth
- runtime git config for commit identity
- branch and push policy

---

# Phase 7 — implement secret management and injection

This phase defines how secrets enter the runner safely.

## Goal
Ensure the runner gets only what it needs, only when it needs it.

## Recommended secret classes

### platform secrets
- GitHub App private key
- OpenAI API key
- optional provider-specific tokens

### project secrets
- repo-specific deploy secrets
- registry tokens
- test service credentials

### environment secrets
- staging deployment credentials
- integration test credentials

## Injection rules
- fetch secrets at job start or action start
- keep them in memory or ephemeral environment variables
- never write them to persistent workspace storage
- minimize the number of secrets available in a job
- use separate permission profiles by action type

## Best pattern
The runner should not have permanent broad secret access.
Instead:
- control plane authorizes the job
- runner receives only the secrets needed for that job
- access is audited

## Output of this phase
A secret classification model and runtime injection process.

---

# Phase 8 — implement the runner bootstrap flow

This phase creates the exact startup sequence a runner follows.

## Goal
Standardize what happens every time a coding job starts.

## Recommended bootstrap sequence

1. runner starts
2. runner authenticates to control plane
3. runner loads workspace metadata
4. runner mounts or opens persistent workspace
5. runner fetches required secrets
6. runner prepares provider auth environment
7. runner prepares git auth environment
8. runner ensures repo is present and up to date
9. runner creates or checks out target branch
10. runner writes job instructions into workspace artifacts
11. runner launches Claude Code or Codex
12. runner streams status back to control plane
13. runner commits and pushes if policy allows
14. runner stores logs and result metadata
15. runner clears transient secrets from process context

## Output of this phase
A deterministic runner startup and shutdown contract.

---

# Phase 9 — define permissions profiles

This phase is where dangerous mode becomes controlled rather than chaotic.

## Goal
Create explicit execution policies for different job types.

## Example profiles

### `read_only_analysis`
Can:
- read repository
- inspect files
- run safe read-only commands

Cannot:
- write files
- commit
- push
- deploy

### `repo_write`
Can:
- read repository
- write files
- run local project commands
- create commits

Cannot:
- push without approval if policy requires
- deploy
- access unrelated workspaces

### `dangerous_repo_write`
Can:
- modify repository broadly
- run install/build/test commands
- create commits
- push if permitted by policy

Requires:
- isolated workspace
- audit logging
- optional approval gates for certain actions

### `deploy_capable`
Can:
- perform repository operations
- execute approved deployment workflow

Requires:
- explicit approval
- stronger audit trail

## Why this matters
“dangerous mode” should be a controlled permissions profile, not a vague concept.

## Output of this phase
A formal permissions matrix for runner behavior.

---

# Phase 10 — implement job execution protocol

This phase defines how the orchestrator tells the runner what to do and how the runner reports progress.

## Goal
Make execution observable and resumable.

## Required job states
- queued
- starting
- preparing_workspace
- authenticating
- running_agent
- testing
- committing
- pushing
- completed
- failed
- blocked
- cancelled

## Minimum status payload

```json
{
  "job_id": "job_123",
  "workspace_id": "ws_001",
  "state": "running_agent",
  "message": "Claude Code is implementing the authentication module",
  "updated_at": "..."
}
```

## Why this matters
Jarvis should be able to say things like:
- “The coding agent is updating the backend now.”
- “It finished coding and is running tests.”
- “It’s blocked because the repo token expired.”

This phase makes that possible.

## Output of this phase
A runner-control-plane protocol for status and lifecycle updates.

---

# Phase 11 — implement Git workflow policy

This phase defines exactly how repositories are modified.

## Goal
Ensure every coding job follows a predictable repository lifecycle.

## Recommended workflow
1. fetch repo
2. sync default branch
3. create or switch to target branch
4. execute coding task
5. run validation commands
6. inspect git diff
7. create commit(s)
8. push branch
9. optionally open or update PR

## Commit strategy recommendations
- use small meaningful commits where possible
- store job ID in commit message footer when useful
- keep branch naming deterministic

## Example branch naming
- `spectalk/job-123-auth-module`
- `feature/spec-auth-foundation`

## Example commit identity
- name: `SpecTalk Bot`
- email: `bot@yourdomain.com`

## Output of this phase
A branching and commit policy for automated coding jobs.

---

# Phase 12 — add approvals for sensitive operations

This phase prevents the runner from taking important irreversible actions without permission.

## Goal
Add human control where necessary without blocking normal coding flow.

## Actions that may require approval
- pushing directly to protected branches
- opening PRs in external repos
- deleting large parts of repository
- changing CI/CD or infrastructure code
- deployment
- use of environment secrets beyond the coding scope

## Approval model
- runner emits approval request event
- control plane records pending approval
- Jarvis asks user naturally
- user approves or rejects
- control plane returns decision to runner

## Output of this phase
An approval workflow connected to conversational UX.

---

# Phase 13 — observability, logs, and audit trail

This phase makes the system supportable.

## Goal
Be able to reconstruct what happened in any coding job.

## What should be logged
- job creation
- workspace chosen
- provider used
- secrets class requested
- git auth method used
- branch created
- commands executed at a safe summary level
- commit hashes
- push results
- failures and reasons
- approvals requested and granted

## Important rule
Do not log raw secrets.
Do not log sensitive tokens.
Do not log private source code indiscriminately if that violates your data policy.

## Output of this phase
A logging and audit plan for runner operations.

---

# Phase 14 — failure recovery and resume strategy

This phase is what makes the system robust instead of brittle.

## Goal
Ensure a job can fail safely and be resumed or retried intelligently.

## Failure classes

### transient
- network interruption
- temporary provider auth failure
- temporary git token issue

### workspace
- disk full
- corrupted repo state
- branch conflict

### provider
- Claude Code failure
- Codex failure
- API quota or rate issue

### policy
- approval denied
- secret unavailable
- repo access revoked

## Resume strategy
- keep workspace metadata updated
- keep job artifacts in workspace
- keep last known git state
- allow job retry from safe checkpoints

## Example checkpoints
- workspace prepared
- repo synced
- branch ready
- agent execution started
- validation passed
- commit created
- push completed

## Output of this phase
A retry and resume model that does not require starting from zero every time.

---

# Phase 15 — connect runner status back to Jarvis

This phase closes the loop with the product experience.

## Goal
Make the isolated coding system feel like part of the live assistant experience.

## What Jarvis should be able to say
- “I started the coding environment.”
- “The repository is ready and the branch has been created.”
- “Claude Code is implementing the backend now.”
- “It finished and pushed the branch.”
- “It needs approval before pushing to the repo.”

## Why this matters
Without this phase, the coding system works technically but feels disconnected from the voice product.

## Output of this phase
A status narration contract between control plane and Jarvis.

---

# Phase 16 — harden for multi-project and multi-user operation

This phase should come after the single-runner flow works well.

## Goal
Prepare the system for real usage at scale.

## Areas to harden
- runner concurrency limits
- workspace ownership rules
- stronger secret scoping
- quota management
- cost controls
- repo allowlists
- egress rules
- branch protection integration
- cleanup policies for stale workspaces

## Output of this phase
A multi-tenant hardening plan.

---

# Recommended Order of Implementation

This is the practical build order.

## Stage 1 — foundations
1. define coding job request schema
2. choose runner substrate
3. define workspace layout
4. build base image

## Stage 2 — authentication and git
5. implement Claude Code auth via Vertex AI
6. implement Codex API key injection
7. implement GitHub App token flow
8. implement git commit identity policy

## Stage 3 — runner lifecycle
9. implement runner bootstrap flow
10. implement job execution protocol
11. implement status reporting

## Stage 4 — safety and control
12. implement permissions profiles
13. implement approval flow
14. implement observability and audit trail
15. implement failure recovery

## Stage 5 — product integration
16. connect control plane to Jarvis updates
17. add natural progress narration
18. add user-facing approval prompts

---

# Minimal v1 Scope

To keep this achievable, the minimal proper version should support:

- one runner substrate
- one repository host
- one git auth method
- one persistent workspace model
- Claude Code auth
- Codex auth
- branch creation
- commit and push
- job status updates
- one approval flow

Do not try to support every repo host, every cloud provider, every runner type, and every deployment model at once.

---

# Final Recommendation

For a proper v1 implementation of SpecTalk’s isolated coding layer on Google Cloud, the best path is:

- use **Cloud Workstations** or **Compute Engine** as the isolated runner substrate
- use **persistent workspace storage** so repository state survives restarts
- use **Claude Code with Vertex AI authentication**
- use **Codex with API key auth from Secret Manager**
- use a **GitHub App** for git authentication
- set git commit identity locally in the runner
- inject secrets at runtime only
- manage permissions through explicit profiles
- stream runner status back through the control plane so Jarvis can narrate progress naturally

This is the clean architecture that avoids repeated setup, avoids human credential dependency, and gives SpecTalk a strong foundation for real automated software creation.

---

# Suggested Next Deliverables

After this document, the next three useful implementation documents would be:

1. **Runner bootstrap spec**
   - exact startup script steps
   - environment variables
   - auth preparation
   - repo preparation sequence

2. **GitHub App integration spec**
   - token minting flow
   - Secret Manager layout
   - clone/push sequence
   - branch and PR policy

3. **Runner control-plane API spec**
   - job submission endpoint
   - status event schema
   - approval request schema
   - retry and cancel actions

