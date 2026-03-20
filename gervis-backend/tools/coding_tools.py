"""Coding mode tools for Gervis — Phase 5.

Three tools that together implement the voice-driven coding workflow:

  1. request_clarification  — ask one focused question at a time (max 3)
  2. generate_and_confirm_prd — research + shape PRD, send to phone for confirmation
  3. confirm_and_dispatch    — user said yes/no; dispatch job or loop back

Flow:
  user: "build me X"
    → Gervis calls request_clarification (up to 3 times)
    → Gervis calls generate_and_confirm_prd
    → phone shows awaiting_confirmation UI
    → user says "yes" → Gervis calls confirm_and_dispatch(confirmed=True)
    → coding job enqueued → FCM on complete
"""

import asyncio
import json
import logging
import uuid

import opik
from google.adk.tools import ToolContext

logger = logging.getLogger(__name__)


@opik.track(name="request_clarification", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def request_clarification(question: str, tool_context: ToolContext) -> str:
    """Ask the user one clarifying question about their coding project.

    Call this up to 3 times (one question per call) before calling
    generate_and_confirm_prd. Tracks the count automatically.

    Args:
        question: A single, concise clarifying question to ask the user.

    Returns:
        The question string — Gervis should speak it naturally.
    """
    state = tool_context.state if tool_context else {}
    conversation_id: str = state.get("conversation_id", "")

    if conversation_id:
        try:
            opik.update_current_trace(thread_id=conversation_id)
        except Exception:
            pass

    logger.info(
        f"[{conversation_id}] request_clarification INVOKED: question={question[:80]}"
    )

    count = state.get("clarification_count", 0)
    state["clarification_count"] = count + 1
    state["pending_clarification"] = question

    # On first clarification, tell the phone we've entered coding mode
    if count == 0 and conversation_id:
        from services.control_channels import send_control_message
        from services.conversation_service import set_conversation_state
        asyncio.create_task(
            send_control_message(conversation_id, {
                "type": "state_update",
                "state": "coding_mode",
            })
        )
        asyncio.create_task(set_conversation_state(conversation_id, "coding_mode"))

    return question


@opik.track(name="generate_and_confirm_prd", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def generate_and_confirm_prd(
    project_idea: str,
    clarifications_json: str,
    tool_context: ToolContext,
) -> str:
    """Research the project and generate a PRD, then send it to the phone for confirmation.

    Call this after collecting up to 3 clarifications (or when you have enough info).
    The phone will display an awaiting_confirmation UI with Yes/No buttons.
    Do NOT dispatch the coding job here — wait for the user to confirm.

    Args:
        project_idea: The full project description (combine original request + clarifications).
        clarifications_json: JSON string of {question: answer} pairs from clarification phase.
                             Pass "{}" if no clarifications were collected.

    Returns:
        A spoken PRD summary for Gervis to read to the user.
    """
    from agents.team_code_pr_designers.designer_agent import generate_prd
    from services.control_channels import send_control_message
    from services.conversation_service import set_conversation_state
    from db.database import AsyncSessionLocal
    from db.models import PendingAction

    state = tool_context.state if tool_context else {}
    conversation_id: str = state.get("conversation_id", "")

    if conversation_id:
        try:
            opik.update_current_trace(thread_id=conversation_id)
        except Exception:
            pass

    logger.info(
        f"[{conversation_id}] generate_and_confirm_prd INVOKED: idea={project_idea[:80]}"
    )

    # Parse clarifications
    clarifications: dict = {}
    if clarifications_json:
        try:
            clarifications = json.loads(clarifications_json)
        except (json.JSONDecodeError, TypeError):
            logger.warning(f"[{conversation_id}] Could not parse clarifications_json")

    # Call the PRD shaper (uses Gemini + Google Search grounding)
    prd = await generate_prd(project_idea, clarifications)

    # Store in ADK session state so confirm_and_dispatch can retrieve it
    state["pending_prd"] = prd
    existing_project = state.get("selected_project")
    preferred_network_host = state.get("preferred_network_host")

    # Build a natural spoken summary
    features_spoken = ", ".join(prd.get("key_features", [])[:3])
    spoken_summary = (
        f"Here's what I'll build: {prd.get('description', '')} "
        f"I'll use {prd.get('tech_stack', 'a modern stack')} for the "
        f"{prd.get('target_platform', 'web')} platform. "
        f"Key features: {features_spoken}. Should I go ahead?"
    )

    # Send PRD to phone — triggers awaiting_confirmation UI
    if conversation_id:
        asyncio.create_task(
            send_control_message(conversation_id, {
                "type": "state_update",
                "state": "awaiting_confirmation",
                "prompt": spoken_summary,
                "prd_summary": prd,
            })
        )

    # Persist a PendingAction so the confirm REST endpoint can also resolve it
    if conversation_id:
        try:
            async with AsyncSessionLocal() as db_session:
                action = PendingAction(
                    conversation_id=uuid.UUID(conversation_id),
                    action_type="confirm_prd",
                    payload={
                        "prd": prd,
                        "existing_project": existing_project,
                        "preferred_network_host": preferred_network_host,
                    },
                    status="pending",
                    description=f"Build project: {prd.get('project_name', 'unknown')}",
                    confirmation_prompt=spoken_summary,
                )
                db_session.add(action)
                await db_session.commit()
            logger.info(f"[{conversation_id}] PendingAction created for PRD confirmation")
        except Exception as e:
            logger.error(f"[{conversation_id}] Failed to create PendingAction: {e}")

        asyncio.create_task(set_conversation_state(conversation_id, "awaiting_confirmation"))

    return spoken_summary


@opik.track(name="confirm_and_dispatch", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def confirm_and_dispatch(
    confirmed: bool,
    change_request: str,
    tool_context: ToolContext,
) -> str:
    """Handle user's yes/no response to the PRD confirmation.

    Call this when:
    - User says yes/confirm/go ahead/sounds good → confirmed=True, change_request=""
    - User says no/change/different → confirmed=False, change_request="what they want changed"

    Args:
        confirmed: True if user accepted the PRD, False if they want changes.
        change_request: Description of what the user wants changed (empty string if confirmed).

    Returns:
        Spoken response for Gervis to deliver to the user.
    """
    from services.control_channels import send_control_message
    from services.conversation_service import set_conversation_state
    from services.job_service import create_job, enqueue_cloud_task, get_active_job_for_conversation
    from db.database import AsyncSessionLocal
    from db.models import PendingAction
    from sqlalchemy import select, update

    state = tool_context.state if tool_context else {}
    conversation_id: str = state.get("conversation_id", "")

    logger.info(
        f"[{conversation_id}] confirm_and_dispatch INVOKED: confirmed={confirmed}, "
        f"change_request={change_request!r:.80}"
    )

    if not conversation_id:
        logger.error("confirm_and_dispatch: no conversation_id in session state — cannot create job")
        return "I had trouble processing that. Please try again."

    if conversation_id:
        try:
            opik.update_current_trace(thread_id=conversation_id)
        except Exception:
            pass

    # Find the most recent pending action for this conversation
    pending_action_id: uuid.UUID | None = None
    pending_action_payload: dict | None = None
    if conversation_id:
        try:
            async with AsyncSessionLocal() as db_session:
                result = await db_session.execute(
                    select(PendingAction)
                    .where(
                        PendingAction.conversation_id == uuid.UUID(conversation_id),
                        PendingAction.action_type == "confirm_prd",
                        PendingAction.status == "pending",
                    )
                    .order_by(PendingAction.created_at.desc())
                    .limit(1)
                )
                pa = result.scalar_one_or_none()
                if pa:
                    pending_action_id = pa.id
                    pending_action_payload = pa.payload or {}
        except Exception as e:
            logger.warning(f"[{conversation_id}] Could not fetch PendingAction: {e}")

    if confirmed:
        prd = state.get("pending_prd", {})
        existing_project = state.get("selected_project")
        preferred_network_host = (
            state.get("preferred_network_host")
            or (pending_action_payload or {}).get("preferred_network_host")
        )
        project_name = prd.get("project_name", "your project")

        active_job = await get_active_job_for_conversation(conversation_id, "coding")
        if active_job:
            logger.info(
                f"[{conversation_id}] Reusing existing active coding job {active_job.id} "
                f"for '{project_name}'"
            )
            return (
                f"I'm already building {project_name}. "
                "I'll update you as soon as it's ready."
            )

        # Resolve the pending action exactly once. If another confirmation already
        # claimed it, reuse the active job instead of creating a duplicate.
        if pending_action_id and conversation_id:
            try:
                async with AsyncSessionLocal() as db_session:
                    result = await db_session.execute(
                        update(PendingAction)
                        .where(
                            PendingAction.id == pending_action_id,
                            PendingAction.status == "pending",
                        )
                        .values(status="resolved")
                    )
                    await db_session.commit()
                    if result.rowcount == 0:
                        active_job = await get_active_job_for_conversation(conversation_id, "coding")
                        if active_job:
                            logger.info(
                                f"[{conversation_id}] PendingAction {pending_action_id} "
                                f"was already resolved; active job is {active_job.id}"
                            )
                            return (
                                f"I'm already building {project_name}. "
                                "I'll update you as soon as it's ready."
                            )
                        return (
                            f"I've already locked in the build for {project_name}. "
                            "I'll let you know as soon as it's ready."
                        )
            except Exception as e:
                logger.warning(f"[{conversation_id}] Could not resolve PendingAction: {e}")

        # Create job and enqueue to Cloud Tasks
        try:
            job = await create_job(conversation_id=conversation_id, job_type="coding")
            job_id = str(job.id)
            user_id = str(job.user_id)

            # Notify phone: running_job state + job_started message
            asyncio.create_task(
                send_control_message(conversation_id, {
                    "type": "state_update",
                    "state": "running_job",
                })
            )
            asyncio.create_task(
                send_control_message(conversation_id, {
                    "type": "job_started",
                    "job_id": job_id,
                    "description": f"Building {project_name}",
                })
            )
            asyncio.create_task(set_conversation_state(conversation_id, "running_job"))
            asyncio.create_task(
                enqueue_cloud_task(
                    job_id=job_id,
                    job_type="coding",
                    conversation_id=conversation_id,
                    user_id=user_id,
                    payload={
                        "prd": prd,
                        "existing_project": existing_project,
                        "preferred_network_host": preferred_network_host,
                    },
                )
            )

            logger.info(
                f"[{conversation_id}] Coding job {job_id} enqueued for '{project_name}'"
            )
            return (
                f"I've started building {project_name}. "
                "I'll send you a notification when it's ready — "
                "you can close the app and check back later."
            )

        except Exception as e:
            logger.error(f"[{conversation_id}] Failed to dispatch coding job: {e}")
            return "I had trouble starting the build. Please try again in a moment."

    else:
        # User wants changes — cancel this PRD, clear state, ask what to change
        if pending_action_id and conversation_id:
            try:
                async with AsyncSessionLocal() as db_session:
                    result = await db_session.execute(
                        select(PendingAction).where(PendingAction.id == pending_action_id)
                    )
                    pa = result.scalar_one_or_none()
                    if pa:
                        pa.status = "cancelled"
                        await db_session.commit()
            except Exception as e:
                logger.warning(f"[{conversation_id}] Could not cancel PendingAction: {e}")

        # Clear PRD from session state so next call to generate_and_confirm_prd starts fresh
        state.pop("pending_prd", None)
        state["clarification_count"] = 0

        asyncio.create_task(
            send_control_message(conversation_id, {
                "type": "state_update",
                "state": "idle",
            })
        )
        asyncio.create_task(set_conversation_state(conversation_id, "idle"))

        if change_request:
            return (
                f"Got it, let me adjust that. You mentioned: {change_request}. "
                "Tell me more about what you'd like and I'll put together a new plan."
            )
        return "No problem. What would you like to change about the plan?"
