"""Google Maps grounding tool.

Uses Gemini's native Google Maps grounding (types.GoogleMaps).
No separate Maps API key required - only the Gemini API key.

Docs: https://ai.google.dev/gemini-api/docs/maps-grounding
Pricing: $25 per 1,000 grounded prompts. Free tier: 500 requests/day.
"""

import asyncio
import logging
from typing import Any

import opik
import services.location_channels as location_channels
from google.adk.tools import ToolContext

logger = logging.getLogger(__name__)


def _resolve_location(location: str, tool_context: ToolContext | None) -> tuple[str | None, bool]:
    normalized = location.strip()
    lowered = normalized.lower()

    if lowered in {"near me", "my location", "current location", "around me", "here"}:
        conversation_id = (tool_context.state or {}).get("conversation_id") if tool_context else None
        cached = location_channels.get_cached(conversation_id) if conversation_id else None

        if cached:
            label = cached.get("location_label")
            if isinstance(label, str) and label.strip():
                return label.strip(), True
            lat = cached.get("latitude")
            lon = cached.get("longitude")
            if lat is not None and lon is not None:
                return f"{lat}, {lon}", True

        return None, False

    return normalized, True


@opik.track(name="find_nearby_places", project_name="gervis",
            capture_input=True, capture_output=True,
            ignore_arguments=["tool_context"])
async def find_nearby_places(
    query: str,
    location: str,
    tool_context: ToolContext,
) -> dict[str, Any]:
    """Find places and location information using Google Maps grounding.

    Args:
        query: What to look for (e.g., "best Italian restaurants", "nearest pharmacy")
        location: Where to search (e.g., "downtown Seattle", "Times Square NYC")

    Returns:
        A dict with spoken_summary (natural language, voice-ready) and sources list.
    """
    from config import settings
    from google import genai
    from google.genai import types

    if not settings.gemini_api_key:
        return {"spoken_summary": "Maps search is not available right now.", "sources": []}

    resolved_location, has_location = _resolve_location(location, tool_context)

    if not has_location or not resolved_location:
        return {
            "spoken_summary": "I can help with that once I know your location. "
            "Share location in Settings or tell me the place name.",
            "sources": [],
        }

    try:
        client = genai.Client(api_key=settings.gemini_api_key)

        prompt = (
            f"{query} near {resolved_location}. "
            "Give a brief, spoken-friendly answer with the top options - "
            "name and a one-line description for each. No markdown or bullet points."
        )

        gen_config_kwargs: dict = {
            "tools": [types.Tool(google_maps=types.GoogleMaps())],
        }

        loop = asyncio.get_event_loop()
        response = await loop.run_in_executor(
            None,
            lambda: client.models.generate_content(
                model="gemini-2.5-flash",
                contents=prompt,
                config=types.GenerateContentConfig(**gen_config_kwargs),
            ),
        )

        spoken = response.text or f"I couldn't find results for {query} near {resolved_location}."

        sources = []
        candidates = getattr(response, "candidates", None) or []
        for candidate in candidates:
            meta = getattr(candidate, "grounding_metadata", None)
            for chunk in getattr(meta, "grounding_chunks", None) or []:
                web = getattr(chunk, "web", None)
                if web:
                    sources.append({
                        "title": getattr(web, "title", ""),
                        "uri": getattr(web, "uri", ""),
                    })

        return {"spoken_summary": spoken, "sources": sources}

    except Exception as e:
        logger.error(f"Maps grounding error for '{query} near {resolved_location}': {e}")
        return {
            "spoken_summary": f"I had trouble finding {query} near {resolved_location}.",
            "sources": [],
        }
