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


@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_db()
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


@app.get("/health")
async def health():
    return {"status": "ok"}
