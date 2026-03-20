"""user_projects table — per-user project registry for cross-session memory

Revision ID: e5f2a3b8c1d7
Revises: d4e1f8a2c5b9
Create Date: 2026-03-20 00:00:00.000000
"""

from typing import Sequence, Union
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID

revision: str = 'e5f2a3b8c1d7'
down_revision: Union[str, None] = 'd4e1f8a2c5b9'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'user_projects',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column('user_id', UUID(as_uuid=True), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('project_name', sa.String(256), nullable=False),
        sa.Column('slug', sa.String(256), nullable=False),
        sa.Column('path', sa.Text(), nullable=True),
        sa.Column('url', sa.Text(), nullable=True),
        sa.Column('last_openclaw_response_id', sa.Text(), nullable=True),
        sa.Column('last_job_id', UUID(as_uuid=True), sa.ForeignKey('jobs.id'), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_unique_constraint('uq_user_project_slug', 'user_projects', ['user_id', 'slug'])
    op.create_index('idx_user_projects_user', 'user_projects', ['user_id'])


def downgrade() -> None:
    op.drop_index('idx_user_projects_user', table_name='user_projects')
    op.drop_constraint('uq_user_project_slug', 'user_projects', type_='unique')
    op.drop_table('user_projects')
