"""Helpers for Gemini Live voice/language preferences."""

from __future__ import annotations

DEFAULT_VOICE_NAME = "Algieba"
DEFAULT_VOICE_LANGUAGE = "en-US"

_SUPPORTED_LANGUAGE_OPTIONS: dict[str, dict[str, str | None]] = {
    "en-US": {
        "voice_language": "en-US",
        "display_name": "English (US)",
        "live_language_code": "en-US",
        "language_instruction": (
            "RESPOND IN ENGLISH (US). YOU MUST RESPOND UNMISTAKABLY IN ENGLISH (US). "
            "IF THE USER SPEAKS ANOTHER LANGUAGE, POLITELY ASK THEM TO CONTINUE IN ENGLISH (US)."
        ),
    },
    "en-GB": {
        "voice_language": "en-GB",
        "display_name": "English (UK)",
        "live_language_code": None,
        "language_instruction": (
            "RESPOND IN ENGLISH (UK). YOU MUST RESPOND UNMISTAKABLY IN ENGLISH (UK), "
            "USING BRITISH WORDING AND PRONUNCIATION. IF THE USER SPEAKS ANOTHER LANGUAGE, "
            "POLITELY ASK THEM TO CONTINUE IN ENGLISH (UK)."
        ),
    },
    "de-DE": {
        "voice_language": "de-DE",
        "display_name": "German",
        "live_language_code": "de-DE",
        "language_instruction": (
            "RESPOND IN GERMAN. YOU MUST RESPOND UNMISTAKABLY IN GERMAN. "
            "IF THE USER SPEAKS ANOTHER LANGUAGE, POLITELY ASK THEM TO CONTINUE IN GERMAN."
        ),
    },
    "es-US": {
        "voice_language": "es-US",
        "display_name": "Spanish",
        "live_language_code": "es-US",
        "language_instruction": (
            "RESPOND IN SPANISH. YOU MUST RESPOND UNMISTAKABLY IN SPANISH. "
            "IF THE USER SPEAKS ANOTHER LANGUAGE, POLITELY ASK THEM TO CONTINUE IN SPANISH."
        ),
    },
}


def build_speech_preferences(
    voice_language: str | None,
    voice_name: str | None = None,
) -> dict[str, str | None]:
    """Return normalized Live API speech preferences for an allowed language."""
    selected = _SUPPORTED_LANGUAGE_OPTIONS.get(voice_language or DEFAULT_VOICE_LANGUAGE)
    if selected is None:
        selected = _SUPPORTED_LANGUAGE_OPTIONS[DEFAULT_VOICE_LANGUAGE]

    return {
        "voice_language": selected["voice_language"],
        "display_name": selected["display_name"],
        "live_language_code": selected["live_language_code"],
        "language_instruction": selected["language_instruction"],
        "voice_name": (voice_name or DEFAULT_VOICE_NAME).strip() or DEFAULT_VOICE_NAME,
    }

