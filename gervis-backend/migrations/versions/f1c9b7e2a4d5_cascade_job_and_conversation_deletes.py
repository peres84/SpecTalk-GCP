"""add cascade delete rules for jobs and conversation-owned records

Revision ID: f1c9b7e2a4d5
Revises: e5f2a3b8c1d7
Create Date: 2026-03-20 16:35:00.000000
"""

from typing import Sequence, Union

from alembic import op


revision: str = "f1c9b7e2a4d5"
down_revision: Union[str, None] = "e5f2a3b8c1d7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _recreate_fk(
    table_name: str,
    constraint_name: str,
    local_cols: list[str],
    remote_table: str,
    remote_cols: list[str],
    ondelete: str | None,
) -> None:
    op.drop_constraint(constraint_name, table_name, type_="foreignkey")
    op.create_foreign_key(
        constraint_name=constraint_name,
        source_table=table_name,
        referent_table=remote_table,
        local_cols=local_cols,
        remote_cols=remote_cols,
        ondelete=ondelete,
    )


def upgrade() -> None:
    _recreate_fk("conversations", "conversations_user_id_fkey", ["user_id"], "users", ["id"], "CASCADE")
    _recreate_fk("turns", "turns_conversation_id_fkey", ["conversation_id"], "conversations", ["id"], "CASCADE")
    _recreate_fk("jobs", "jobs_conversation_id_fkey", ["conversation_id"], "conversations", ["id"], "CASCADE")
    _recreate_fk("jobs", "jobs_user_id_fkey", ["user_id"], "users", ["id"], "CASCADE")
    _recreate_fk(
        "pending_actions",
        "pending_actions_conversation_id_fkey",
        ["conversation_id"],
        "conversations",
        ["id"],
        "CASCADE",
    )
    _recreate_fk(
        "resume_events",
        "resume_events_conversation_id_fkey",
        ["conversation_id"],
        "conversations",
        ["id"],
        "CASCADE",
    )
    _recreate_fk("resume_events", "resume_events_job_id_fkey", ["job_id"], "jobs", ["id"], "CASCADE")
    _recreate_fk("user_integrations", "user_integrations_user_id_fkey", ["user_id"], "users", ["id"], "CASCADE")
    _recreate_fk("user_projects", "user_projects_user_id_fkey", ["user_id"], "users", ["id"], "CASCADE")
    _recreate_fk("user_projects", "user_projects_last_job_id_fkey", ["last_job_id"], "jobs", ["id"], "SET NULL")
    _recreate_fk("assets", "assets_user_id_fkey", ["user_id"], "users", ["id"], "CASCADE")
    _recreate_fk("assets", "assets_conversation_id_fkey", ["conversation_id"], "conversations", ["id"], "CASCADE")


def downgrade() -> None:
    _recreate_fk("assets", "assets_conversation_id_fkey", ["conversation_id"], "conversations", ["id"], None)
    _recreate_fk("assets", "assets_user_id_fkey", ["user_id"], "users", ["id"], None)
    _recreate_fk("user_projects", "user_projects_last_job_id_fkey", ["last_job_id"], "jobs", ["id"], None)
    _recreate_fk("user_projects", "user_projects_user_id_fkey", ["user_id"], "users", ["id"], None)
    _recreate_fk("user_integrations", "user_integrations_user_id_fkey", ["user_id"], "users", ["id"], None)
    _recreate_fk("resume_events", "resume_events_job_id_fkey", ["job_id"], "jobs", ["id"], None)
    _recreate_fk(
        "resume_events",
        "resume_events_conversation_id_fkey",
        ["conversation_id"],
        "conversations",
        ["id"],
        None,
    )
    _recreate_fk(
        "pending_actions",
        "pending_actions_conversation_id_fkey",
        ["conversation_id"],
        "conversations",
        ["id"],
        None,
    )
    _recreate_fk("jobs", "jobs_user_id_fkey", ["user_id"], "users", ["id"], None)
    _recreate_fk("jobs", "jobs_conversation_id_fkey", ["conversation_id"], "conversations", ["id"], None)
    _recreate_fk("turns", "turns_conversation_id_fkey", ["conversation_id"], "conversations", ["id"], None)
    _recreate_fk("conversations", "conversations_user_id_fkey", ["user_id"], "users", ["id"], None)
