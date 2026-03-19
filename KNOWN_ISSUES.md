# Known Issues

Last updated: 2026-03-19

This document tracks active issues that are important for day-to-day development and release readiness.
It is a support document, not the source of truth for ownership or prioritization. Use the team issue tracker
for assignment, status changes, and implementation planning.

## Frontend Agent

### [HIGH] Auto-open on job completion notification does not open the related conversation

Status: **Resolved** (2026-03-20)
Area: Android app / Frontend agent
Environment: Local device

Summary:
When a job-complete notification arrives and the user has enabled the setting `auto-open on job complete`,
the app should automatically open the conversation tied to that notification and clearly inform the user what
is happening. Right now, the flow only works when the user taps the notification manually.

Expected behavior:
- The app reads the conversation ID from the incoming notification.
- If `auto-open on job complete` is enabled, the app opens that specific conversation automatically.
- The agent/UI tells the user that the completed job is ready and that the related conversation has been opened.

Actual behavior:
- The toggle is present in settings, but the automatic open flow does not complete.
- The conversation only opens when the notification is tapped manually.

Impact:
- Creates inconsistency between the setting and the actual behavior.
- Makes job completion feel unreliable and harder to trust.

Notes:
- Verify end-to-end handling across FCM reception, notification routing, navigation, and conversation restoration.
- Confirm that the notification payload includes the correct conversation ID and that it is propagated into navigation.

### [MEDIUM] Wake confirmation sound plays before Gemini connection is ready

Status: **Resolved** (2026-03-20)
Area: Android app / Voice UX
Environment: Local device

Summary:
The wake-word confirmation sound is currently triggered too early. The sound should play only when the Gemini
connection is actually open and the app is in the `listening` state. Playing it before that gives the user
false feedback that the agent is ready when it is not.

Expected behavior:
- Trigger the wake confirmation sound only after the Gemini voice connection is established.
- The sound should represent the exact moment the assistant is ready and listening.

Actual behavior:
- The sound plays before the Gemini connection is fully open.

Impact:
- Confusing voice interaction feedback.
- Can cause users to begin speaking before audio capture and upstream connection are ready.

Notes:
- Align sound playback with the same state transition that marks the session as ready to listen.
- Review hotword detection, session startup, and backend voice connection handshake timing.

## Backend Agent

### [HIGH] Current Google Cloud observability is not enough to reliably debug agent behavior

Status: Open
Area: Backend agent / Observability
Environment: Google Cloud

Summary:
The current Google Cloud observability setup is not sufficient for reliably tracking agent conversations,
logic flow, and debugging failures. Backend logs alone are not enough to explain why the agent responded in
a certain way or where a workflow broke.

Expected behavior:
- Developers can inspect a conversation end-to-end.
- Developers can trace agent execution, tool usage, response generation, and failure points with enough detail
  to debug production and staging issues.

Actual behavior:
- Tracing and debugging in Google Cloud are difficult and incomplete for current needs.
- It is hard to follow conversation flow and logic execution across agent steps.

Impact:
- Slows down debugging of production bugs.
- Makes regressions and subtle workflow failures harder to isolate.

Planned improvement:
- Re-implement Opik for agent observability and execution tracing.
- Track agent conversations, spans, and logic execution under project name `gervis`.
- Use Opik account username `javier-peres`.
- Reference: https://www.comet.com/docs/opik/

Notes:
- Define a consistent correlation key such as `flow_id` or `conversation_id` across Android, backend, and tracing.
- Capture at least request metadata, tool calls, model responses, failures, and final user-visible outcomes.
