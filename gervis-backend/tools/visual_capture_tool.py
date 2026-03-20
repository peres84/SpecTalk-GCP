"""Tool for requesting an automatic glasses capture from the Android client."""

import logging

import opik
from google.adk.tools import ToolContext

from services.control_channels import send_control_message
from services.visual_capture_channels import wait_for_capture

logger = logging.getLogger(__name__)


@opik.track(
    name="request_visual_capture",
    project_name="gervis",
    capture_input=True,
    capture_output=True,
    ignore_arguments=["tool_context"],
)
async def request_visual_capture(tool_context: ToolContext) -> dict:
    """Ask the Android client to capture the current Meta glasses view.

    Only succeeds when the active session reports that a glasses camera stream is
    ready and the user is currently in listening mode.
    """
    state = tool_context.state if tool_context else {}
    conversation_id: str | None = state.get("conversation_id")
    if not conversation_id:
        logger.warning("request_visual_capture: no conversation_id in session state")
        return {
            "available": False,
            "capture_requested": False,
            "message": "No active conversation is available for visual capture.",
        }

    if not state.get("glasses_camera_ready", False):
        return {
            "available": False,
            "capture_requested": False,
            "message": "Meta glasses camera is not ready in this session.",
        }

    if not state.get("listening_enabled", True):
        return {
            "available": False,
            "capture_requested": False,
            "message": "Automatic glasses capture only works while listening mode is active.",
        }

    delivered = await send_control_message(
        conversation_id,
        {
            "type": "request_visual_capture",
            "source": "glasses",
        },
    )
    if not delivered:
        return {
            "available": False,
            "capture_requested": False,
            "message": "The phone is not connected right now.",
        }

    logger.info(f"[{conversation_id}] Requested automatic glasses capture")
    result = await wait_for_capture(conversation_id)
    if result is None:
        return {
            "available": False,
            "capture_requested": True,
            "image_received": False,
            "message": "The glasses capture did not arrive in time.",
        }

    if not result.get("ok"):
        return {
            "available": False,
            "capture_requested": True,
            "image_received": False,
            "message": result.get("reason") or "The glasses capture failed.",
        }

    return {
        "available": True,
        "capture_requested": True,
        "image_received": True,
        "message": "The glasses image is now available in the conversation context.",
    }
