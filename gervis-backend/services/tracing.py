"""Observability using OpenTelemetry + Google Cloud Trace, and Opik (Comet ML).

Auto-enabled in production when GCP_PROJECT env var is set (Cloud Run service account
needs roles/cloudtrace.agent — no API key required).

Controlled by ENABLE_TRACING env var:

  ENABLE_TRACING=cloud   — export spans to Google Cloud Trace  (auto-on when GCP_PROJECT set)
  ENABLE_TRACING=console — print spans to stdout               (useful for local debugging)
  ENABLE_TRACING=off     — disable completely
  unset                  — cloud if GCP_PROJECT is set, otherwise off

Opik is enabled independently when OPIK_API_KEY is set.
Opik stores full turn text (unlike OTel which only stores metadata).
"""

import functools
import logging
import os
from typing import Any, Callable

logger = logging.getLogger(__name__)

_tracer = None  # opentelemetry.trace.Tracer | None
_opik_client = None  # opik.Opik | None — module-level singleton (avoids re-init per turn)
_active_traces: dict[str, Any] = {}  # conversation_id → opik.Trace


def setup_tracing() -> None:
    global _tracer

    mode = os.getenv("ENABLE_TRACING", "").strip().lower()

    # Auto-detect: enable cloud trace in production when GCP_PROJECT is present
    if not mode:
        if os.getenv("GCP_PROJECT") or os.getenv("GOOGLE_CLOUD_PROJECT"):
            mode = "cloud"

    if mode and mode != "off":
        if mode == "cloud":
            project_id = os.getenv("GCP_PROJECT") or os.getenv("GOOGLE_CLOUD_PROJECT")
            logger.info(f"Cloud Trace setup: ENABLE_TRACING={mode!r} GCP_PROJECT={project_id!r}")
            if not project_id:
                logger.error(
                    "ENABLE_TRACING=cloud but GCP_PROJECT is not set — tracing disabled"
                )
            else:
                try:
                    from opentelemetry import trace
                    from opentelemetry.exporter.cloud_trace import CloudTraceSpanExporter  # type: ignore[import]
                    from opentelemetry.sdk.trace import TracerProvider
                    from opentelemetry.sdk.trace.export import BatchSpanProcessor

                    provider = TracerProvider()
                    provider.add_span_processor(
                        BatchSpanProcessor(CloudTraceSpanExporter(project_id=project_id))
                    )
                    trace.set_tracer_provider(provider)
                    _tracer = trace.get_tracer("gervis-backend")
                    logger.info(f"Cloud Trace enabled (project={project_id})")
                except ImportError:
                    logger.error(
                        "opentelemetry-exporter-gcp-trace not installed — tracing disabled. "
                        "Run: uv add opentelemetry-exporter-gcp-trace"
                    )

        elif mode == "console":
            from opentelemetry import trace
            from opentelemetry.sdk.trace import TracerProvider
            from opentelemetry.sdk.trace.export import ConsoleSpanExporter, SimpleSpanProcessor

            provider = TracerProvider()
            provider.add_span_processor(SimpleSpanProcessor(ConsoleSpanExporter()))
            trace.set_tracer_provider(provider)
            _tracer = trace.get_tracer("gervis-backend")
            logger.info("Tracing enabled → console (stdout)")

        else:
            logger.warning(
                f"ENABLE_TRACING={mode!r} not recognised — use 'cloud', 'console', or 'off'"
            )

    # Opik is always initialised independently of OTel — just needs OPIK_API_KEY
    _setup_opik()


def _setup_opik() -> None:
    global _opik_client
    from config import settings
    if not settings.opik_api_key:
        return
    try:
        import opik

        # Set env vars so @opik.track decorators (which use global config) work
        # alongside the manual opik.Opik() client used for voice session traces.
        # On Cloud Run these are already set via Secret Manager injection, so
        # setdefault won't override them.
        import os
        os.environ.setdefault("OPIK_API_KEY", settings.opik_api_key)
        os.environ.setdefault("OPIK_WORKSPACE", settings.opik_workspace)
        os.environ.setdefault("OPIK_PROJECT_NAME", settings.opik_project_name)

        # Do NOT call opik.configure() — it's a CLI wizard, not a server API.
        # Pass api_key, workspace, project_name directly to constructor.
        _opik_client = opik.Opik(
            api_key=settings.opik_api_key,
            workspace=settings.opik_workspace,
            project_name=settings.opik_project_name,
        )
        logger.info(
            f"Opik enabled (workspace={settings.opik_workspace}, project={settings.opik_project_name})"
        )
    except ImportError:
        logger.error("opik not installed — run: uv add opik")
    except Exception as e:
        logger.error(f"Opik init failed: {e}")


def get_tracer():
    """Return the configured OpenTelemetry Tracer, or None if tracing is disabled."""
    return _tracer


def get_opik_client():
    """Return the Opik client singleton, or None if Opik is not configured."""
    return _opik_client


def opik_session_start(conversation_id: str, user_id: str) -> None:
    """Open an Opik trace for a voice session."""
    client = get_opik_client()
    if not client:
        return
    try:
        trace = client.trace(
            name="voice_session",
            thread_id=conversation_id,
            input={"user_id": user_id, "conversation_id": conversation_id},
            metadata={"source": "gervis_voice"},
        )
        _active_traces[conversation_id] = trace
    except Exception:
        pass


def opik_session_end(conversation_id: str) -> None:
    """Close and flush the Opik trace for a voice session."""
    trace = _active_traces.pop(conversation_id, None)
    if trace:
        try:
            trace.end()
        except Exception:
            pass


def record_voice_turn_opik(conversation_id: str, role: str, text: str) -> None:
    """Add a voice turn span to the active Opik trace — stores full text (unlike OTel)."""
    trace = _active_traces.get(conversation_id)
    if not trace:
        return
    try:
        trace.span(
            name=f"voice_turn.{role}",
            type="general",
            input={"role": role},
            output={"text": text},
            metadata={"conversation_id": conversation_id},
        )
    except Exception:
        pass


def record_voice_turn(conversation_id: str, role: str, text: str) -> None:
    """Record a completed voice turn as an OTel span.

    Fire-and-forget safe — never raises. Text length is recorded but not the
    full text to avoid storing PII in traces.
    """
    tracer = get_tracer()
    if not tracer:
        return
    try:
        with tracer.start_as_current_span(f"voice_turn.{role}") as span:
            span.set_attribute("conversation_id", conversation_id)
            span.set_attribute("role", role)
            span.set_attribute("text_length", len(text))
    except Exception:
        pass


def trace_span(name: str):
    """Decorator: wraps an async function in an OTel span. No-op if tracing is off.

    Usage:
        @trace_span("find_nearby_places")
        async def find_nearby_places(...):
            ...
    """
    def decorator(fn: Callable) -> Callable:
        @functools.wraps(fn)
        async def wrapper(*args: Any, **kwargs: Any) -> Any:
            tracer = get_tracer()
            if not tracer:
                return await fn(*args, **kwargs)
            with tracer.start_as_current_span(name) as span:
                span.set_attribute("function", fn.__name__)
                return await fn(*args, **kwargs)
        return wrapper
    return decorator
