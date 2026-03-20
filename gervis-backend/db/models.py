import uuid
from datetime import datetime
from sqlalchemy import String, DateTime, Text, Integer, Boolean, ForeignKey, Index, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.dialects.postgresql import UUID, JSONB

from db.database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    firebase_uid: Mapped[str] = mapped_column(String(128), unique=True, nullable=False, index=True)
    email: Mapped[str] = mapped_column(String(256), unique=True, nullable=False, index=True)
    display_name: Mapped[str | None] = mapped_column(String(256), nullable=True)
    push_token: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    conversations: Mapped[list["Conversation"]] = relationship(back_populates="user")
    jobs: Mapped[list["Job"]] = relationship(back_populates="user")
    assets: Mapped[list["Asset"]] = relationship(back_populates="user")
    integrations: Mapped[list["UserIntegration"]] = relationship(back_populates="user")
    projects: Mapped[list["UserProject"]] = relationship(back_populates="user")


class Conversation(Base):
    __tablename__ = "conversations"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    state: Mapped[str] = mapped_column(String(64), nullable=False, default="idle")
    last_turn_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    pending_resume_count: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    working_memory: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    user: Mapped["User"] = relationship(back_populates="conversations")
    turns: Mapped[list["Turn"]] = relationship(back_populates="conversation")
    jobs: Mapped[list["Job"]] = relationship(back_populates="conversation")
    pending_actions: Mapped[list["PendingAction"]] = relationship(back_populates="conversation")
    resume_events: Mapped[list["ResumeEvent"]] = relationship(back_populates="conversation")
    assets: Mapped[list["Asset"]] = relationship(back_populates="conversation")


class Turn(Base):
    __tablename__ = "turns"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    conversation_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("conversations.id"), nullable=False
    )
    role: Mapped[str] = mapped_column(String(32), nullable=False)
    text: Mapped[str | None] = mapped_column(Text, nullable=True)
    event_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    structured_data: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    conversation: Mapped["Conversation"] = relationship(back_populates="turns")

    __table_args__ = (
        Index("idx_turns_conversation", "conversation_id", "created_at"),
    )


class Job(Base):
    __tablename__ = "jobs"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    conversation_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("conversations.id"), nullable=False
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    job_type: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="queued")
    artifacts: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    spoken_completion_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    display_completion_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    error_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    conversation: Mapped["Conversation"] = relationship(back_populates="jobs")
    user: Mapped["User"] = relationship(back_populates="jobs")
    resume_events: Mapped[list["ResumeEvent"]] = relationship(back_populates="job")


class PendingAction(Base):
    __tablename__ = "pending_actions"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    conversation_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("conversations.id"), nullable=False
    )
    action_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    payload: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="pending")
    description: Mapped[str] = mapped_column(Text, nullable=False)
    confirmation_prompt: Mapped[str] = mapped_column(Text, nullable=False)
    resolved_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    resolution: Mapped[str | None] = mapped_column(String(32), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    conversation: Mapped["Conversation"] = relationship(back_populates="pending_actions")


class ResumeEvent(Base):
    __tablename__ = "resume_events"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    conversation_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("conversations.id"), nullable=False
    )
    job_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("jobs.id"), nullable=True
    )
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    spoken_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    display_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    artifacts: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    is_acknowledged: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    conversation: Mapped["Conversation"] = relationship(back_populates="resume_events")
    job: Mapped["Job | None"] = relationship(back_populates="resume_events")

    __table_args__ = (
        Index("idx_resume_events_conversation", "conversation_id", "is_acknowledged"),
    )


class UserIntegration(Base):
    """Encrypted third-party integration credentials stored per user.

    URL and token are Fernet-encrypted at rest (see services/encryption_service.py).
    service_name is free-form but validated by the API layer (e.g. "openclaw").
    """
    __tablename__ = "user_integrations"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    service_name: Mapped[str] = mapped_column(String(64), nullable=False)
    encrypted_url: Mapped[str] = mapped_column(Text, nullable=False)
    encrypted_token: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    user: Mapped["User"] = relationship(back_populates="integrations")

    __table_args__ = (
        UniqueConstraint("user_id", "service_name", name="uq_user_integration"),
    )


class UserProject(Base):
    """Per-user project registry — persists project metadata across sessions.

    Created/updated when a coding job completes successfully.
    Enables cross-session lookups like "edit my project langdrill".
    """
    __tablename__ = "user_projects"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    # Display name as provided in the PRD (e.g. "LangDrill")
    project_name: Mapped[str] = mapped_column(String(256), nullable=False)
    # Normalized slug for fuzzy voice matching (lowercase, alphanumeric only, e.g. "langdrill")
    slug: Mapped[str] = mapped_column(String(256), nullable=False)
    # Absolute path on the OpenClaw VPS
    path: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Live HTTP URL (if nginx deployment succeeded)
    url: Mapped[str | None] = mapped_column(Text, nullable=True)
    # Most recent OpenClaw response_id — used for context chaining on edit jobs
    last_openclaw_response_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    # FK to the most recent job that created/updated this project
    last_job_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("jobs.id"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    user: Mapped["User"] = relationship(back_populates="projects")

    __table_args__ = (
        UniqueConstraint("user_id", "slug", name="uq_user_project_slug"),
        Index("idx_user_projects_user", "user_id"),
    )


class Asset(Base):
    __tablename__ = "assets"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id"), nullable=False
    )
    conversation_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("conversations.id"), nullable=True
    )
    mime_type: Mapped[str] = mapped_column(String(128), nullable=False)
    storage_url: Mapped[str] = mapped_column(Text, nullable=False)
    caption: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str | None] = mapped_column(String(32), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user: Mapped["User"] = relationship(back_populates="assets")
    conversation: Mapped["Conversation | None"] = relationship(back_populates="assets")
