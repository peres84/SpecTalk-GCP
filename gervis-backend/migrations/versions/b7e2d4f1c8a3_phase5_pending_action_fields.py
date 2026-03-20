"""phase5 pending action fields: action_type, payload, status

Revision ID: b7e2d4f1c8a3
Revises: a3f8c1d2e9b4
Create Date: 2026-03-19 00:00:00.000000

Adds three columns to pending_actions required for Phase 5 coding mode:
  - action_type: identifies the kind of pending action (e.g. "confirm_prd")
  - payload: JSONB blob for the action's data (e.g. the generated PRD dict)
  - status: lifecycle state — "pending" | "resolved" | "cancelled"
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision: str = 'b7e2d4f1c8a3'
down_revision: Union[str, None] = 'a3f8c1d2e9b4'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column('pending_actions', sa.Column('action_type', sa.String(64), nullable=True))
    op.add_column('pending_actions', sa.Column('payload', JSONB(), nullable=True))
    op.add_column(
        'pending_actions',
        sa.Column('status', sa.String(32), nullable=False, server_default='pending'),
    )


def downgrade() -> None:
    op.drop_column('pending_actions', 'status')
    op.drop_column('pending_actions', 'payload')
    op.drop_column('pending_actions', 'action_type')
