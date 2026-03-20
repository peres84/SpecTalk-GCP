"""ADK Runner singleton — owns the Gemini Live session infrastructure."""

import logging
from google.adk.runners import InMemoryRunner
from google.adk.agents.live_request_queue import LiveRequestQueue
from google.genai import types

from config import settings

logger = logging.getLogger(__name__)


def _resolve_audio_modality(RunConfig):
    """Return the correct AUDIO enum value for RunConfig.response_modalities.

    Tries known locations for the Modality enum across ADK/genai SDK versions.
    Falls back to the string "AUDIO" only if the enum cannot be found, which
    produces a Pydantic serializer warning but still works functionally.
    """
    # Attempt 1: google.genai.types.Modality (preferred, most recent SDK layout)
    try:
        from google.genai import types as genai_types
        return genai_types.Modality.AUDIO
    except AttributeError:
        pass

    # Attempt 2: resolve from the RunConfig field's annotation args
    try:
        import typing, get_annotations  # noqa: F401
    except ImportError:
        pass
    try:
        hints = RunConfig.model_fields.get("response_modalities")
        if hints:
            origin = getattr(hints.annotation, "__args__", None)
            if origin:
                for arg in origin:
                    member = getattr(arg, "AUDIO", None)
                    if member is not None:
                        return member
    except Exception:
        pass

    # Fallback: string — triggers a Pydantic warning but works
    return "AUDIO"


def _build_run_config():
    """Build the RunConfig for a native-audio Gemini Live session.

    Only uses fields available in google-adk 1.1.1:
      streaming_mode, input_audio_transcription,
      output_audio_transcription, speech_config, max_llm_calls.

    Fields added in later ADK versions (session_resumption, realtime_input_config)
    are injected only when the installed version supports them.
    """
    from google.adk.agents.run_config import RunConfig

    supported = set(RunConfig.model_fields.keys())

    kwargs: dict = dict(
        streaming_mode="bidi",
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
    )

    # Explicitly set AUDIO response modality — required by the native-audio
    # model. Without this the Gemini Live API rejects the connection with
    # "Cannot extract voices from a non-audio request" (error 1007).
    #
    # Pydantic warns if we pass the string "AUDIO" instead of the enum.
    # Resolve the Modality enum from RunConfig's own field annotation so we
    # always pass the right type regardless of where the SDK puts the enum.
    if "response_modalities" in supported:
        modality_audio = _resolve_audio_modality(RunConfig)
        kwargs["response_modalities"] = [modality_audio]

    if "session_resumption" in supported:
        kwargs["session_resumption"] = types.SessionResumptionConfig(transparent=True)
    else:
        logger.debug("RunConfig.session_resumption not available in this ADK version — skipping")

    if "realtime_input_config" in supported:
        try:
            kwargs["realtime_input_config"] = types.RealtimeInputConfig(
                automatic_activity_detection=types.AutomaticActivityDetection(
                    start_of_speech_sensitivity=types.StartSensitivity.START_SENSITIVITY_LOW,
                    end_of_speech_sensitivity=types.EndSensitivity.END_SENSITIVITY_LOW,
                    silence_duration_ms=320,
                )
            )
        except AttributeError:
            logger.debug("AutomaticActivityDetection fields not available — skipping VAD config")
    else:
        logger.debug("RunConfig.realtime_input_config not available in this ADK version — skipping")

    return RunConfig(**kwargs)


class GeminiLiveClient:
    """Singleton that owns the ADK Runner and session service."""

    _runner: InMemoryRunner | None = None

    def initialize(self) -> None:
        """Initialize the ADK runner. Call once at app startup."""
        # Import here to avoid circular import at module load time
        from agents.orchestrator import create_gervis_agent

        agent = create_gervis_agent(settings.gemini_model)
        self._runner = InMemoryRunner(
            app_name=settings.adk_app_name,
            agent=agent,
        )
        logger.info(f"GeminiLiveClient initialized with model={settings.gemini_model}")

    @property
    def runner(self) -> InMemoryRunner:
        if self._runner is None:
            raise RuntimeError("GeminiLiveClient not initialized — call initialize() first")
        return self._runner

    async def get_or_create_session(self, user_id: str, session_id: str):
        """Get an existing ADK session or create a new one.

        conversation_id is stored as session state so tool_context.state can
        look up the location_channels registry without relying on dict mutation
        after the fact (InMemorySessionService may return copies, not references).
        """
        session = await self.runner.session_service.get_session(
            app_name=settings.adk_app_name,
            user_id=user_id,
            session_id=session_id,
        )
        if not session:
            session = await self.runner.session_service.create_session(
                app_name=settings.adk_app_name,
                user_id=user_id,
                session_id=session_id,
                state={"conversation_id": session_id},
            )
        return session

    def start_live_session(
        self, user_id: str, session_id: str, live_request_queue: LiveRequestQueue
    ):
        """Start a Gemini Live streaming session. Returns async generator of events."""
        run_config = _build_run_config()
        return self.runner.run_live(
            user_id=user_id,
            session_id=session_id,
            live_request_queue=live_request_queue,
            run_config=run_config,
        )

    def new_request_queue(self) -> LiveRequestQueue:
        return LiveRequestQueue()


# Singleton instance
gemini_live_client = GeminiLiveClient()
