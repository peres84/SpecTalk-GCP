"""Observability using OpenTelemetry + Google Cloud Trace.

Auto-enabled in production when GCP_PROJECT env var is set (Cloud Run service account
needs roles/cloudtrace.agent — no API key required).

Controlled by ENABLE_TRACING env var:

  ENABLE_TRACING=cloud   — export spans to Google Cloud Trace  (auto-on when GCP_PROJECT set)
  ENABLE_TRACING=console — print spans to stdout               (useful for local debugging)
  ENABLE_TRACING=off     — disable completely
  unset                  — cloud if GCP_PROJECT is set, otherwise off

No OPIK_API_KEY or external credentials needed. Cloud Run ADC handles Cloud Trace auth.
"""

import functools
import logging
import os
from typing import Any, Callable

logger = logging.getLogger(__name__)

_tracer = None  # opentelemetry.trace.Tracer | None


def setup_tracing() -> None:
    global _tracer

    mode = os.getenv("ENABLE_TRACING", "").strip().lower()

    # Auto-detect: enable cloud trace in production when GCP_PROJECT is present
    if not mode:
        if os.getenv("GCP_PROJECT") or os.getenv("GOOGLE_CLOUD_PROJECT"):
            mode = "cloud"
        else:
            return  # development default: tracing off

    if mode == "off":
        return

    if mode == "cloud":
        project_id = os.getenv("GCP_PROJECT") or os.getenv("GOOGLE_CLOUD_PROJECT")
        if not project_id:
            logger.error(
                "ENABLE_TRACING=cloud but GCP_PROJECT is not set — tracing disabled"
            )
            return
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


def get_tracer():
    """Return the configured OpenTelemetry Tracer, or None if tracing is disabled."""
    return _tracer


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
