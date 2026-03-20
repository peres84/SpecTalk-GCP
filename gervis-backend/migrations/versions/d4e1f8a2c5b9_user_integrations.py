"""user_integrations table — encrypted third-party credentials per user

Revision ID: d4e1f8a2c5b9
Revises: b7e2d4f1c8a3
Create Date: 2026-03-20 00:00:00.000000

Adds user_integrations table for storing Fernet-encrypted integration
credentials (e.g. OpenClaw URL + hook token) per user.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import UUID


revision: str = 'd4e1f8a2c5b9'
down_revision: Union[str, None] = 'b7e2d4f1c8a3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'user_integrations',
        sa.Column('id', UUID(as_uuid=True), primary_key=True, nullable=False),
        sa.Column('user_id', UUID(as_uuid=True), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('service_name', sa.String(64), nullable=False),
        sa.Column('encrypted_url', sa.Text(), nullable=False),
        sa.Column('encrypted_token', sa.Text(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_unique_constraint(
        'uq_user_integration', 'user_integrations', ['user_id', 'service_name']
    )
    op.create_index('idx_user_integrations_user', 'user_integrations', ['user_id'])


def downgrade() -> None:
    op.drop_index('idx_user_integrations_user', table_name='user_integrations')
    op.drop_constraint('uq_user_integration', 'user_integrations', type_='unique')
    op.drop_table('user_integrations')
