import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase

from config import settings

engine = create_async_engine(settings.database_url, echo=False, pool_pre_ping=True)
AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


async def init_db():
    # Verify DB connectivity at startup. Schema is managed by Alembic migrations.
    async with engine.connect() as conn:
        await conn.execute(sa.text("SELECT 1"))


async def get_db() -> AsyncSession:
    async with AsyncSessionLocal() as session:
        yield session
