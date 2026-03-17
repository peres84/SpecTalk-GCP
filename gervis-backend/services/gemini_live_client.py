"""ADK Runner singleton — owns the Gemini Live session infrastructure."""

import logging
from google.adk.runners import InMemoryRunner
from google.adk.agents.live_request_queue import LiveRequestQueue
from google.genai import types

from config import settings

logger = logging.getLogger(__name__)


def _build_run_config():
    """Build the RunConfig for a native-audio Gemini Live session.

    Only uses fields available in google-adk 1.1.1:
      streaming_mode, response_modalities, input_audio_transcription,
      output_audio_transcription, speech_config, max_llm_calls.

    Fields added in later ADK versions (session_resumption, realtime_input_config)
    are injected only when the installed version supports them.
    """
    from google.adk.agents.run_config import RunConfig

    supported = set(RunConfig.model_fields.keys())

    kwargs: dict = dict(
        streaming_mode="bidi",
        response_modalities=["AUDIO"],
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
    )

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
        """Get an existing ADK session or create a new one."""
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
            )
        return session

    def start_live_session(self, session, live_request_queue: LiveRequestQueue):
        """Start a Gemini Live streaming session. Returns async generator of events."""
        run_config = _build_run_config()
        return self.runner.run_live(
            session=session,
            live_request_queue=live_request_queue,
            run_config=run_config,
        )

    def new_request_queue(self) -> LiveRequestQueue:
        return LiveRequestQueue()


# Singleton instance
gemini_live_client = GeminiLiveClient()
