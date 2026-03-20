import os
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from config import settings
from db.database import init_db
from api import auth as auth_router
from api import voice as voice_router
from api import conversations as conversations_router
from api import jobs as jobs_router
from api import notifications as notifications_router
from api.internal import jobs as internal_jobs_router
from api.internal import openclaw_callback as openclaw_callback_router
from api import integrations as integrations_router
from ws import voice_handler as ws_voice_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Set Gemini API key before initializing ADK.
    # Remove GEMINI_API_KEY FIRST, then set GOOGLE_API_KEY — this closes the window
    # where both env vars exist simultaneously, which causes genai SDK to initialize
    # two model connections and produces double audio responses.
    # Expose integration encryption key to encryption_service (reads from env)
    if settings.integration_encryption_key:
        os.environ["INTEGRATION_ENCRYPTION_KEY"] = settings.integration_encryption_key

    if settings.gemini_api_key:
        os.environ.pop("GEMINI_API_KEY", None)   # remove alias first
        os.environ.pop("GOOGLE_GENAI_API_KEY", None)  # remove any other aliases
        os.environ["GOOGLE_API_KEY"] = settings.gemini_api_key  # set the one ADK reads

    from services.tracing import setup_tracing
    setup_tracing()

    await init_db()

    from services.gemini_live_client import gemini_live_client
    gemini_live_client.initialize()

    yield


app = FastAPI(
    title="SpecTalk Backend",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.allowed_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth_router.router, prefix="/auth", tags=["auth"])
app.include_router(voice_router.router, prefix="/voice", tags=["voice"])
app.include_router(conversations_router.router, prefix="/conversations", tags=["conversations"])
app.include_router(jobs_router.router, prefix="/jobs", tags=["jobs"])
app.include_router(notifications_router.router, prefix="/notifications", tags=["notifications"])
app.include_router(internal_jobs_router.router, prefix="/internal", tags=["internal"])
app.include_router(openclaw_callback_router.router, prefix="/internal", tags=["internal"])
app.include_router(integrations_router.router, prefix="/integrations", tags=["integrations"])
app.include_router(ws_voice_router.router, prefix="/ws", tags=["websocket"])


@app.get("/health")
async def health():
    return {"status": "ok"}
