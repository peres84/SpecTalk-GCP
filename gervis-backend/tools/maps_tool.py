"""Google Maps grounding tool.

Uses Gemini's native Google Maps grounding (types.GoogleMaps).
No separate Maps API key required — only the Gemini API key.

Docs: https://ai.google.dev/gemini-api/docs/maps-grounding
Pricing: $25 per 1,000 grounded prompts. Free tier: 500 requests/day.
"""

import asyncio
import logging

logger = logging.getLogger(__name__)


async def find_nearby_places(query: str, location: str) -> dict:
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

    try:
        client = genai.Client(api_key=settings.gemini_api_key)

        prompt = (
            f"{query} near {location}. "
            "Give a brief, spoken-friendly answer with the top options — "
            "name and a one-line description for each. No markdown or bullet points."
        )

        gen_config_kwargs: dict = {
            "tools": [types.Tool(google_maps=types.GoogleMaps())],
        }

        # Sync call — run in executor to avoid blocking the event loop
        loop = asyncio.get_event_loop()
        response = await loop.run_in_executor(
            None,
            lambda: client.models.generate_content(
                model="gemini-2.5-flash",
                contents=prompt,
                config=types.GenerateContentConfig(**gen_config_kwargs),
            ),
        )

        spoken = response.text or f"I couldn't find results for {query} near {location}."

        # Extract grounding sources from metadata
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
        logger.error(f"Maps grounding error for '{query} near {location}': {e}")
        return {
            "spoken_summary": f"I had trouble finding {query} near {location}.",
            "sources": [],
        }
