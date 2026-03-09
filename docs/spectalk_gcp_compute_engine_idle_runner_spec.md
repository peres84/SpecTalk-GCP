# SpecTalk on Google Cloud: Compute Engine Idle Runner Specification

## Purpose

This document defines the recommended **Google Cloud deployment pattern** for SpecTalk’s isolated coding execution layer using:

- **Compute Engine VM** as the coding runner
- **Persistent Disk** as the shared workspace storage for all projects
- **Programmatic VM start/stop** for cost control
- **An idle shutdown policy** to stop the VM automatically after inactivity

This is the recommended **cost-conscious v1 architecture** for users who want:

- isolated execution for Claude Code or Codex
- persistent project folders across sessions
- the ability to resume work later
- low idle cost when the system is not being used often

---

# 1. High-Level Recommendation

For SpecTalk v1 on Google Cloud, use:

## Public/control layer
- **Cloud Run** for control plane APIs and orchestration endpoints

## Private execution layer
- **One Compute Engine VM** as the runner host
- **One Persistent Disk** mounted at `/workspaces`
- **One runner service** installed on the VM

## Cost behavior
- VM is **started only when needed**
- VM is **stopped after 2 hours of inactivity**
- Persistent Disk remains attached or available, so all project folders survive

This gives you the cheapest practical architecture without losing project state.

---

# 2. Why Compute Engine for v1

Compute Engine is the best fit for the coding runner in v1 because it gives you:

- full shell access
- full process control
- support for Claude Code / Codex / MCP tooling
- persistent disks
- simple startup/shutdown automation
- easy stop/start reuse of the same machine

It is a better fit than Cloud Run for the coding sandbox because the coding sandbox needs:

- a durable filesystem
- repo workspaces
- project folders
- shell-level execution
- broad tool compatibility

---

# 3. Core Design Principle

Use this model:

> **one persistent VM identity + one persistent workspace disk + dynamic idle shutdown**

This means:

- you do **not** create a fresh VM for every job by default
- you keep the **same VM instance**
- you stop it when idle
- you start it again when new work arrives
- all workspaces remain on disk under `/workspaces`

---

# 4. Architecture Overview

```text
User
  ↓
Jarvis
  ↓
Control Plane (Cloud Run)
  ↓
Orchestrator API (Cloud Run)
  ↓
Compute Engine VM lifecycle manager
  ↓
Runner VM
  ├── runner service
  ├── Claude Code / Codex
  ├── MCP runtime
  └── /workspaces (Persistent Disk)
```

---

# 5. Main Components

## 5.1 Control Plane
The central system that:
- receives requests from Jarvis
- creates coding jobs
- decides when to start the VM
- tracks runner activity
- stops the VM after inactivity
- handles blockers and approvals

## 5.2 Orchestrator
The job coordination system that:
- prepares coding jobs
- checks runner availability
- assigns work
- receives progress and blocker events
- resumes jobs after user input or approvals

## 5.3 Runner VM
A Compute Engine VM that:
- boots on demand
- runs the SpecTalk runner service
- mounts the persistent workspace disk
- executes coding jobs with Claude Code or Codex
- reports status back to the orchestrator

## 5.4 Persistent Disk
A durable disk mounted at `/workspaces` that stores:
- all project folders
- repo state
- metadata
- logs
- artifacts
- workspace registry

---

# 6. Storage Model

All projects live on the same persistent storage volume.

## Mount path

```text
/workspaces
```

## Recommended layout

```text
/workspaces/
  _registry/
    workspaces.db
    runners.db
  project-a/
    repo/
    specs/
    tasks/
    artifacts/
    logs/
    metadata.json
  project-b/
    repo/
    specs/
    tasks/
    artifacts/
    logs/
    metadata.json
```

## Rules
- all project folders stay on the persistent disk
- the VM may stop, but the disk remains
- on the next startup, the runner reloads the registry and project metadata
- memory is not the source of truth
- persistent storage is the source of truth

---

# 7. Why One Disk for All Projects Works in v1

This is a good v1 strategy when:
- one runner VM is active at a time
- concurrency is low
- cost simplicity matters more than multi-host scale
- you want all project folders available in one place

## Benefits
- simple to reason about
- easy resume behavior
- cheap compared with more complex shared storage systems
- easier backup and snapshot strategy

## Limitation
If you later need multiple VMs to access the same project folders at once, you should reevaluate the storage design.

---

# 8. VM Lifecycle Model

The VM should be reused, not recreated, in the normal flow.

## Normal behavior
1. VM is stopped while unused
2. new coding job arrives
3. orchestrator starts the VM
4. VM boots
5. runner service starts and registers
6. orchestrator assigns the job
7. runner executes the work
8. if no jobs are active for 2 hours, orchestrator stops the VM
9. project folders remain on Persistent Disk
10. next job starts the same VM again

## Important principle
The VM is disposable from a compute perspective, but persistent from an identity/workspace perspective.

---

# 9. Programmatic Start/Stop Control

The VM must be controlled programmatically by the orchestrator or a lifecycle manager.

## Start conditions
The orchestrator should start the VM when:
- a new coding job is created
- the selected runner VM is stopped
- the job requires the cloud runner target

## Stop conditions
The orchestrator should stop the VM when:
- no active jobs exist
- no job is in a critical finalization phase
- no unresolved push/commit action is pending
- the VM has been idle for at least 120 minutes

## Required capability
The orchestrator must be able to:
- check VM state
- start the VM
- stop the VM
- optionally wait until the runner registers again

---

# 10. Idle Shutdown Policy

This is the key cost-control feature.

## Policy
The cloud runner VM should automatically stop after **2 hours of inactivity**.

## Definition of inactivity
A VM is considered inactive when all of the following are true:
- no active coding job is running
- no validation/test phase is running
- no pending push/commit operation is in progress
- no unresolved internal runner action is still executing
- runner status is `idle`
- `last_activity_at` is older than 120 minutes

## Recommended config

```yaml
runner_idle_shutdown:
  enabled: true
  idle_timeout_minutes: 120
  require_no_active_jobs: true
  require_no_pending_push: true
  require_runner_status_idle: true
  flush_logs_before_stop: true
```

## Important note
This is not a native Compute Engine “idle timer.”
This behavior must be implemented by SpecTalk.

---

# 11. How the VM Turns Back On

The same VM should normally be started again, not replaced.

## Resume flow
1. new job arrives
2. orchestrator checks runner VM state
3. if VM is stopped, orchestrator starts it
4. startup script or system service launches the runner
5. runner mounts `/workspaces`
6. runner loads workspace registry
7. runner re-registers with orchestrator
8. job is assigned

This allows project folders to remain intact across sessions.

---

# 12. Startup Behavior

The VM should automatically prepare itself on boot.

## Recommended mechanisms
- startup script
- systemd service for the runner

## On boot, the VM should:
1. mount the Persistent Disk at `/workspaces`
2. verify required directories exist
3. start the runner service
4. runner loads registry metadata
5. runner registers with orchestrator
6. runner begins heartbeat

## Important rule
The VM should not wait for a human to SSH in and manually start the runner.

---

# 13. Shutdown Behavior

Before the VM is stopped, the system should perform a graceful shutdown path.

## Before stop, ensure:
- no active job is mid-write if avoidable
- logs are flushed
- workspace metadata is synced
- final runner state is written

## Recommended mechanism
- shutdown script
- final runner callback on SIGTERM / shutdown event

## Goal
Prevent workspace confusion or partial state loss.

---

# 14. Billing Model

## While the VM is running
You pay for:
- VM compute
- Persistent Disk
- any other attached billable resources

## While the VM is stopped
You no longer pay compute for the VM itself, but you still pay for:
- Persistent Disk storage
- some other attached resources if applicable

## Cost strategy for SpecTalk
This is why the design should be:
- keep disk all the time
- run VM only during active usage or near-active usage
- stop after 2 hours idle

---

# 15. Why Not Create a New VM Every Time

You could do that later, but it is not the best v1 design.

## Why not for v1
- more complexity
- more bootstrapping overhead
- more workspace reattachment complexity
- less natural project resume behavior

## Better v1 approach
Keep the same VM instance and same disk.
Use stop/start.

---

# 16. Runner Responsibilities on the VM

The VM image should include the SpecTalk runner service.

## Preinstalled in the image
- Claude Code CLI
- Codex CLI
- MCP runtime/client
- language runtimes
- git
- runner daemon
- workspace manager
- status reporter

## Runner responsibilities
- register with orchestrator
- heartbeat
- load workspace registry
- prepare job environments
- run Claude Code / Codex
- emit progress and blocker events
- commit/push when allowed
- mark itself idle or busy

---

# 17. How Inactivity Is Detected

The best place to decide inactivity is the control plane/orchestrator, not the VM alone.

## Recommended model
The runner reports:
- `status: idle|busy`
- `active_job_count`
- `last_activity_at`

The orchestrator evaluates:
- whether jobs are still running
- whether the runner is idle
- whether blockers require the VM to remain online
- whether the idle timeout has passed

## Why this is better
The control plane has the full system context.
The VM only knows its local state.

---

# 18. State Needed for Safe Stop

Before stopping the VM, the orchestrator should check:
- no job state is `running_agent`
- no job state is `testing`
- no job state is `committing`
- no job state is `pushing`
- runner is not in `busy`
- no unresolved local cleanup is pending

## Optional safety flag
Use a runner flag like:

```json
{
  "safe_to_stop": true
}
```

This can help prevent accidental stop in the middle of critical work.

---

# 19. Control Plane API Requirements

The control plane or lifecycle manager needs these capabilities.

## VM lifecycle actions
- `get_vm_state(vm_id)`
- `start_vm(vm_id)`
- `stop_vm(vm_id)`
- `wait_for_runner_registration(runner_id)`

## Runner coordination
- `assign_job(runner_id, job_id)`
- `get_runner_status(runner_id)`
- `record_last_activity(runner_id, timestamp)`

## Idle policy actions
- `evaluate_idle_shutdown(runner_id)`
- `stop_if_idle(runner_id)`

---

# 20. Recommended Workflow

## A. New coding job arrives
1. control plane creates job
2. orchestrator checks runner VM state
3. if stopped, start VM
4. wait until runner registers
5. assign job
6. runner executes

## B. Runner becomes idle
1. runner reports `idle`
2. orchestrator records `last_activity_at`
3. no new job arrives
4. after 120 minutes, orchestrator checks safe-stop conditions
5. VM is stopped

## C. Later, user resumes another project
1. user asks Jarvis to continue work
2. control plane selects the cloud runner
3. orchestrator starts same VM
4. runner reloads `/workspaces`
5. project folder is still present
6. runner resumes work on that project

---

# 21. Recommended Persistent Metadata

Because memory is temporary, the runner should keep persistent metadata on disk.

## Project metadata example

```json
{
  "project_id": "proj_001",
  "name": "payments-api",
  "path": "/workspaces/payments-api",
  "repo": "github/acme/payments-api",
  "active_branch": "spectalk/job-123-auth-module",
  "last_job_id": "job_123",
  "last_opened_at": "2026-03-07T10:20:00Z",
  "status": "idle"
}
```

## Registry role
The registry lets the runner rebuild in-memory state after a reboot.

---

# 22. Recommended Compute Engine Setup

## Suggested v1 shape
- 1 small Linux VM
- 1 boot disk
- 1 additional Persistent Disk mounted at `/workspaces`
- startup script enabled
- shutdown script enabled
- service account with only required permissions

## Service account should be able to
- access allowed Google Cloud APIs needed by the runner
- authenticate Claude Code via Vertex if using that path
- read only the needed secrets if required by policy

---

# 23. Backup and Recovery

Persistent workspaces should still be backed up.

## Recommended strategy
- use Persistent Disk snapshots
- take periodic snapshots of the workspace disk
- optionally snapshot before major upgrades or migrations

## Why this matters
If the VM is fine but the disk content is damaged or deleted, snapshots protect project history.

---

# 24. Security Guidelines

## Required rules
- the runner VM is not public-facing unless absolutely necessary
- the control plane is the only system that starts/stops the VM
- all runner traffic is authenticated
- secrets are not stored in workspace folders
- repo credentials are injected at runtime, not hardcoded on disk

## Optional enhancements
- private networking
- firewall rules allowing only control-plane access
- repo allowlists
- approval gates for push or deployment

---

# 25. Minimal v1 Implementation Plan

## Stage 1
- create Compute Engine VM
- attach Persistent Disk
- mount it at `/workspaces`
- install runner service

## Stage 2
- add startup script to launch runner
- add shutdown script to flush state
- implement runner registration
- implement runner heartbeat

## Stage 3
- implement control-plane VM state checks
- implement programmatic start
- implement programmatic stop
- implement 2-hour idle policy

## Stage 4
- implement workspace registry
- store all projects as folders on the disk
- restore registry on VM restart

## Stage 5
- integrate Claude Code / Codex execution
- integrate git auth and commit flow
- integrate blocker/approval handling

---

# 26. Final Recommendation

For a cheap and practical cloud deployment of SpecTalk’s coding runner on Google Cloud, the best v1 design is:

- one **Compute Engine VM**
- one shared **Persistent Disk** for all project folders
- one **runner service** on the VM
- **programmatic VM start/stop** from the orchestrator
- automatic **stop after 2 hours of inactivity**
- reuse the **same VM** when new work arrives

This design is simple, cost-conscious, and highly compatible with the execution model you want.

It gives you:
- a real isolated machine for dangerous-mode coding
- durable project folders across sessions
- clean resume behavior
- low cost when the system is not used often

