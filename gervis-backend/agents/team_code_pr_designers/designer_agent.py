"""PRD Shaper — generates a structured Product Requirements Document.

Given a raw voice idea and collected clarification answers, researches current
best practices via Google Search grounding and returns a structured PRD dict.

This is a one-shot call (not a multi-turn agent): prompt-in, structured-dict-out.
Uses the same GEMINI_API_KEY as the rest of the backend — no new credentials needed.
"""

import asyncio
import json
import logging
import os
import re

logger = logging.getLogger(__name__)

# Text model for PRD generation (not the audio preview model used for voice).
# Use the versioned ID — the alias "gemini-2.0-flash" was removed by Google.
_PRD_MODEL = "gemini-2.0-flash-001"


def _build_prd_prompt(project_idea: str, clarifications: dict) -> str:
    clarification_lines = ""
    if clarifications:
        clarification_lines = "\n".join(
            f"  - {k}: {v}" for k, v in clarifications.items()
        )
        clarification_section = f"\nClarifications from the user:\n{clarification_lines}\n"
    else:
        clarification_section = "\nNo additional clarifications provided.\n"

    return f"""You are a senior technical product manager creating a concise project requirements document.

Project idea: {project_idea}
{clarification_section}
Research the latest best practices for this type of project (tech stack, architecture patterns,
recommended libraries, common pitfalls) using your knowledge and search capabilities.

Return a JSON object with EXACTLY these fields — no extra fields, no markdown, just JSON:
{{
  "project_name": "short memorable name (2-4 words)",
  "description": "2-3 sentences: what it does, who it's for, core value proposition",
  "target_platform": "one of: web | mobile | backend | fullstack",
  "key_features": ["feature 1", "feature 2", "feature 3", "feature 4", "feature 5"],
  "tech_stack": "specific, opinionated recommendation e.g. React 19 + Vite + FastAPI + PostgreSQL",
  "scope_estimate": "one of: small | medium | large"
}}

Be specific and opinionated — not generic. Reflect current ecosystem knowledge.
Return ONLY the JSON object."""


def _extract_json(text: str) -> dict:
    """Extract JSON from model response, handling markdown code blocks."""
    text = text.strip()

    # Strip ```json ... ``` or ``` ... ``` blocks
    code_block = re.search(r"```(?:json)?\s*([\s\S]+?)\s*```", text)
    if code_block:
        text = code_block.group(1).strip()

    # Find first { ... } JSON object
    brace_match = re.search(r"\{[\s\S]+\}", text)
    if brace_match:
        text = brace_match.group(0)

    return json.loads(text)


def _fallback_prd(project_idea: str, clarifications: dict) -> dict:
    """Return a minimal PRD when parsing fails — better than crashing."""
    name_words = project_idea.split()[:3]
    project_name = " ".join(w.capitalize() for w in name_words) or "New Project"
    platform = "web"
    for key in ("platform", "type"):
        value = clarifications.get(key, "").lower()
        if "mobile" in value or "android" in value or "ios" in value:
            platform = "mobile"
            break
        if "backend" in value or "api" in value:
            platform = "backend"
            break

    return {
        "project_name": project_name,
        "description": f"A project to {project_idea.lower()}. Built with modern tooling and best practices.",
        "target_platform": platform,
        "key_features": [
            "User authentication",
            "Core feature set",
            "Data persistence",
            "Responsive UI",
            "API integration",
        ],
        "tech_stack": "React + FastAPI + PostgreSQL",
        "scope_estimate": "medium",
    }


async def generate_prd(project_idea: str, clarifications: dict) -> dict:
    """Generate a structured PRD from a project idea and clarification answers.

    Uses Gemini with Google Search grounding to research current best practices
    before generating the PRD — so tech stack recommendations reflect the current
    ecosystem rather than generic boilerplate.

    Args:
        project_idea: Raw voice description of what the user wants to build.
        clarifications: Dict of question→answer pairs from the clarification phase.

    Returns:
        Structured PRD dict with project_name, description, target_platform,
        key_features, tech_stack, and scope_estimate.
    """
    api_key = os.environ.get("GOOGLE_API_KEY", "")
    if not api_key:
        logger.warning("generate_prd: GOOGLE_API_KEY not set — returning fallback PRD")
        return _fallback_prd(project_idea, clarifications)

    prompt = _build_prd_prompt(project_idea, clarifications)

    try:
        from google import genai
        from google.genai import types as genai_types

        client = genai.Client(api_key=api_key)

        # Use Google Search grounding so the model can research current best practices.
        # We request plain text (not JSON mime-type) so grounding works without conflicts,
        # then parse the JSON from the model's text response ourselves.
        config = genai_types.GenerateContentConfig(
            tools=[genai_types.Tool(google_search=genai_types.GoogleSearch())],
            temperature=0.3,
        )

        response = await asyncio.to_thread(
            client.models.generate_content,
            model=_PRD_MODEL,
            contents=prompt,
            config=config,
        )

        raw_text = response.text or ""
        logger.debug(f"generate_prd raw response ({len(raw_text)} chars): {raw_text[:300]}")

        prd = _extract_json(raw_text)

        # Validate required keys are present
        required = {"project_name", "description", "target_platform", "key_features", "tech_stack", "scope_estimate"}
        missing = required - set(prd.keys())
        if missing:
            logger.warning(f"generate_prd: missing keys {missing}, using fallback for them")
            fallback = _fallback_prd(project_idea, clarifications)
            for key in missing:
                prd[key] = fallback[key]

        logger.info(f"generate_prd: PRD generated for '{prd.get('project_name')}'")
        return prd

    except Exception as e:
        logger.error(f"generate_prd failed: {e} — returning fallback PRD", exc_info=True)
        return _fallback_prd(project_idea, clarifications)
