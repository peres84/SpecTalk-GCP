"""Observability setup for the SpecTalk backend.

Controlled by the ENABLE_ADK_TRACING environment variable:

  ENABLE_ADK_TRACING=opik    — send traces to Opik (Comet) cloud UI  ← recommended
  ENABLE_ADK_TRACING=console — print spans to stdout (no UI, fallback)
  ENABLE_ADK_TRACING=cloud   — export to Google Cloud Trace (requires GOOGLE_CLOUD_PROJECT)
  unset / anything else      — tracing disabled

Opik credentials (add to .env):
  OPIK_API_KEY        — from comet.com → Settings → API Keys  (required)
  OPIK_WORKSPACE      — your Comet username or org slug        (required)
  OPIK_PROJECT_NAME   — label shown in the Opik UI             (default: gervis)

After setup call get_opik_client() to log traces, or use @opik.track on functions.
"""

import logging
import os
from typing import Optional

logger = logging.getLogger(__name__)

_opik_client = None


def setup_tracing() -> None:
    global _opik_client

    mode = os.getenv("ENABLE_ADK_TRACING", "").strip().lower()
    if not mode:
        return

    if mode == "opik":
        api_key = os.getenv("OPIK_API_KEY", "")
        workspace = os.getenv("OPIK_WORKSPACE", "")
        project = os.getenv("OPIK_PROJECT_NAME", "gervis")

        if not api_key:
            logger.error("ENABLE_ADK_TRACING=opik but OPIK_API_KEY is not set — tracing disabled")
            return
        if not workspace:
            logger.error("ENABLE_ADK_TRACING=opik but OPIK_WORKSPACE is not set — tracing disabled")
            return

        import opik

        # configure() writes api_key + workspace to ~/.opik.config so all
        # subsequent opik.Opik() calls and @opik.track decorators pick them up.
        # project_name is NOT a configure() param — pass it per-client instead.
        opik.configure(
            api_key=api_key,
            workspace=workspace,
            use_local=False,
            force=True,
        )

        _opik_client = opik.Opik(
            project_name=project,
            workspace=workspace,
            api_key=api_key,
        )
        logger.info(f"Opik tracing enabled (workspace={workspace}, project={project})")

    elif mode == "console":
        from opentelemetry import trace
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import ConsoleSpanExporter, SimpleSpanProcessor
        provider = TracerProvider()
        provider.add_span_processor(SimpleSpanProcessor(ConsoleSpanExporter()))
        trace.set_tracer_provider(provider)
        logger.info("ADK tracing enabled → console (stdout)")

    elif mode == "cloud":
        project_id = os.getenv("GOOGLE_CLOUD_PROJECT")
        if not project_id:
            logger.error("ENABLE_ADK_TRACING=cloud but GOOGLE_CLOUD_PROJECT is not set — tracing disabled")
            return
        from opentelemetry import trace
        from opentelemetry.exporter.cloud_trace import CloudTraceSpanExporter
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor
        provider = TracerProvider()
        provider.add_span_processor(
            BatchSpanProcessor(CloudTraceSpanExporter(project_id=project_id))
        )
        trace.set_tracer_provider(provider)
        logger.info(f"ADK tracing enabled → Cloud Trace (project={project_id})")

    else:
        logger.warning(
            f"ENABLE_ADK_TRACING={mode!r} not recognised — use 'opik', 'console', or 'cloud'"
        )


def get_opik_client():
    """Return the configured Opik client, or None if tracing is disabled."""
    return _opik_client
