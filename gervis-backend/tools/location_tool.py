"""Location tool: fetches the user's current location from the Android client.

When the agent needs the user's location (e.g. for Maps queries), it calls this tool.
The tool sends {"type": "request_location"} over the active voice WebSocket and waits
up to 6 seconds for a {"type": "location_response"} from Android.

Uses a module-level channel registry (services.location_channels) so non-serializable
objects (asyncio.Event, async callable) are never stored in ADK session state.
The session state only carries the plain-string conversation_id for the lookup.
"""

import logging
from typing import Any

import opik
import services.location_channels as location_channels
from google.adk.tools import ToolContext

logger = logging.getLogger(__name__)


@opik.track(name="get_user_location", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def get_user_location(tool_context: ToolContext) -> dict[str, Any]:
    """Get the user's current GPS location from their phone.

    Call this before find_nearby_places when the user says "near me", "around here",
    or otherwise implies their current location without naming a place.

    Returns a dict with:
      available       — bool, False if location could not be obtained
      coordinates     — "lat,lon" string for precise Maps queries (use this for find_nearby_places)
      location_label  — human-readable name (e.g. "Dachau, Germany"), less precise
      latitude        — raw float
      longitude       — raw float
      accuracy_meters — GPS accuracy radius (omitted if unknown)

    Always pass 'coordinates' to find_nearby_places when available — it is far more
    precise than location_label for nearby searches.
    """
    state = tool_context.state if tool_context else {}
    conversation_id: str | None = state.get("conversation_id")

    if not conversation_id:
        logger.warning("get_user_location: no conversation_id in session state")
        return {
            "available": False,
            "message": "Location is not available in this session.",
        }

    cached = location_channels.get_cached(conversation_id)
    if cached:
        return _format_location(cached)

    location = await location_channels.request_and_wait(conversation_id)
    if not location:
        return {
            "available": False,
            "message": "The phone did not respond with a location. "
                       "Ask the user to enable location sharing in Settings.",
        }

    return _format_location(location)


def _format_location(data: dict) -> dict[str, Any]:
    lat = data.get("latitude")
    lon = data.get("longitude")
    label = data.get("location_label")
    result: dict[str, Any] = {"available": True}
    if lat is not None and lon is not None:
        result["latitude"] = lat
        result["longitude"] = lon
        result["coordinates"] = f"{lat},{lon}"
    if label:
        result["location_label"] = label
    if data.get("accuracy_meters") is not None:
        result["accuracy_meters"] = data["accuracy_meters"]
    return result
