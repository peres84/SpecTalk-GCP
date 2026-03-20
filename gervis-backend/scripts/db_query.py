#!/usr/bin/env python
"""
Database query utility for retrieving information from SpecTalk tables.

Usage:
  uv run python scripts/db_query.py users [--limit N]
  uv run python scripts/db_query.py conversations [--user-id UUID]
  uv run python scripts/db_query.py jobs [--conversation-id UUID] [--status STATUS]
  uv run python scripts/db_query.py turns [--conversation-id UUID] [--limit N]
  uv run python scripts/db_query.py pending-actions [--conversation-id UUID]
  uv run python scripts/db_query.py resume-events [--conversation-id UUID]
  uv run python scripts/db_query.py assets [--user-id UUID] [--conversation-id UUID]
  uv run python scripts/db_query.py summary [--user-id UUID]
"""

import asyncio
import argparse
import sys
from datetime import datetime
from sqlalchemy import text
from pathlib import Path

# Add parent directory to path
sys.path.insert(0, str(Path(__file__).parent.parent))

from db.database import engine


async def get_users(limit: int = 20) -> None:
    """List all users."""
    async with engine.connect() as conn:
        result = await conn.execute(text(
            "SELECT id, email, display_name, push_token, created_at, updated_at "
            "FROM users ORDER BY created_at DESC LIMIT :limit"
        ), {"limit": limit})
        rows = result.fetchall()

        if not rows:
            print("No users found.")
            return

        print(f"\n{'ID (first 8)':<12} {'Email':<30} {'Name':<20} {'Created':<20}")
        print("-" * 82)
        for row in rows:
            uid, email, name, token, created, updated = row
            uid_short = str(uid)[:8]
            email = email or "N/A"
            name = name or "N/A"
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{uid_short:<12} {email:<30} {name:<20} {created_str:<20}")


async def get_conversations(user_id: str = None, limit: int = 20) -> None:
    """List conversations."""
    async with engine.connect() as conn:
        if user_id:
            result = await conn.execute(text(
                "SELECT id, user_id, state, created_at, updated_at "
                "FROM conversations WHERE user_id = :uid ORDER BY created_at DESC LIMIT :limit"
            ), {"uid": user_id, "limit": limit})
            print(f"\nConversations for user {user_id[:8]}:")
        else:
            result = await conn.execute(text(
                "SELECT id, user_id, state, created_at, updated_at "
                "FROM conversations ORDER BY created_at DESC LIMIT :limit"
            ), {"limit": limit})
            print(f"\nAll conversations (limit: {limit}):")

        rows = result.fetchall()
        if not rows:
            print("No conversations found.")
            return

        print(f"{'Conv ID (first 8)':<18} {'User ID (first 8)':<18} {'State':<12} {'Created':<20}")
        print("-" * 68)
        for row in rows:
            cid, uid, state, created, updated = row
            cid_short = str(cid)[:8]
            uid_short = str(uid)[:8]
            state = state or "N/A"
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{cid_short:<18} {uid_short:<18} {state:<12} {created_str:<20}")


async def get_jobs(conversation_id: str = None, status: str = None, limit: int = 50) -> None:
    """List jobs."""
    async with engine.connect() as conn:
        query = "SELECT id, conversation_id, user_id, job_type, status, created_at, updated_at FROM jobs WHERE 1=1"
        params = {}

        if conversation_id:
            query += " AND conversation_id = :cid"
            params["cid"] = conversation_id

        if status:
            query += " AND status = :status"
            params["status"] = status

        query += f" ORDER BY created_at DESC LIMIT :limit"
        params["limit"] = limit

        result = await conn.execute(text(query), params)
        rows = result.fetchall()

        if not rows:
            print("No jobs found.")
            return

        print(f"\n{'Job ID (first 8)':<18} {'Type':<20} {'Status':<12} {'Created':<20}")
        print("-" * 70)
        for row in rows:
            jid, cid, uid, job_type, job_status, created, updated = row
            jid_short = str(jid)[:8]
            job_type = job_type or "N/A"
            job_status = job_status or "N/A"
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{jid_short:<18} {job_type:<20} {job_status:<12} {created_str:<20}")


async def get_turns(conversation_id: str = None, limit: int = 20) -> None:
    """List turns in conversation."""
    async with engine.connect() as conn:
        if conversation_id:
            result = await conn.execute(text(
                "SELECT id, role, event_type, text, created_at "
                "FROM turns WHERE conversation_id = :cid ORDER BY created_at DESC LIMIT :limit"
            ), {"cid": conversation_id, "limit": limit})
            print(f"\nTurns for conversation {conversation_id[:8]}:")
        else:
            result = await conn.execute(text(
                "SELECT id, role, event_type, text, created_at "
                "FROM turns ORDER BY created_at DESC LIMIT :limit"
            ), {"limit": limit})
            print(f"\nAll turns (limit: {limit}):")

        rows = result.fetchall()
        if not rows:
            print("No turns found.")
            return

        print(f"{'Role':<12} {'Event Type':<20} {'Text (first 50)':<52} {'Time':<20}")
        print("-" * 104)
        for row in rows:
            tid, role, event_type, text, created = row
            role = role or "N/A"
            event_type = event_type or "N/A"
            text_preview = (text or "")[:50].replace("\n", " ")
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{role:<12} {event_type:<20} {text_preview:<52} {created_str:<20}")


async def get_pending_actions(conversation_id: str = None) -> None:
    """List pending actions."""
    async with engine.connect() as conn:
        if conversation_id:
            result = await conn.execute(text(
                "SELECT id, action_type, status, description, created_at "
                "FROM pending_actions WHERE conversation_id = :cid ORDER BY created_at DESC"
            ), {"cid": conversation_id})
            print(f"\nPending actions for conversation {conversation_id[:8]}:")
        else:
            result = await conn.execute(text(
                "SELECT id, action_type, status, description, created_at "
                "FROM pending_actions ORDER BY created_at DESC"
            ))
            print("\nAll pending actions:")

        rows = result.fetchall()
        if not rows:
            print("No pending actions found.")
            return

        print(f"{'Type':<20} {'Status':<12} {'Description':<40} {'Created':<20}")
        print("-" * 92)
        for row in rows:
            aid, action_type, status, desc, created = row
            action_type = action_type or "N/A"
            status = status or "N/A"
            desc = (desc or "")[:40]
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{action_type:<20} {status:<12} {desc:<40} {created_str:<20}")


async def get_resume_events(conversation_id: str = None) -> None:
    """List resume events."""
    async with engine.connect() as conn:
        if conversation_id:
            result = await conn.execute(text(
                "SELECT id, event_type, is_acknowledged, created_at "
                "FROM resume_events WHERE conversation_id = :cid ORDER BY created_at DESC"
            ), {"cid": conversation_id})
            print(f"\nResume events for conversation {conversation_id[:8]}:")
        else:
            result = await conn.execute(text(
                "SELECT id, event_type, is_acknowledged, created_at "
                "FROM resume_events ORDER BY created_at DESC"
            ))
            print("\nAll resume events:")

        rows = result.fetchall()
        if not rows:
            print("No resume events found.")
            return

        print(f"{'Event Type':<20} {'Acknowledged':<15} {'Created':<20}")
        print("-" * 55)
        for row in rows:
            eid, event_type, acknowledged, created = row
            event_type = event_type or "N/A"
            ack = "Yes" if acknowledged else "No"
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{event_type:<20} {ack:<15} {created_str:<20}")


async def get_assets(user_id: str = None, conversation_id: str = None) -> None:
    """List assets."""
    async with engine.connect() as conn:
        query = "SELECT id, mime_type, source, created_at FROM assets WHERE 1=1"
        params = {}

        if user_id:
            query += " AND user_id = :uid"
            params["uid"] = user_id

        if conversation_id:
            query += " AND conversation_id = :cid"
            params["cid"] = conversation_id

        query += " ORDER BY created_at DESC"

        result = await conn.execute(text(query), params)
        rows = result.fetchall()

        if not rows:
            print("No assets found.")
            return

        print(f"\n{'MIME Type':<30} {'Source':<15} {'Created':<20}")
        print("-" * 65)
        for row in rows:
            aid, mime_type, source, created = row
            mime_type = mime_type or "N/A"
            source = source or "N/A"
            created_str = created.strftime("%Y-%m-%d %H:%M") if created else "N/A"
            print(f"{mime_type:<30} {source:<15} {created_str:<20}")


async def get_summary(user_id: str = None) -> None:
    """Get summary statistics for the database."""
    async with engine.connect() as conn:
        # Total counts
        result = await conn.execute(text("SELECT COUNT(*) FROM users"))
        user_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM conversations"))
        conv_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM turns"))
        turn_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM jobs"))
        job_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM pending_actions"))
        pending_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM resume_events"))
        resume_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM assets"))
        asset_count = result.scalar()

        result = await conn.execute(text("SELECT COUNT(*) FROM user_integrations"))
        integration_count = result.scalar()

        print("\n=== Database Summary ===\n")
        print(f"Users:              {user_count}")
        print(f"Conversations:      {conv_count}")
        print(f"Turns:              {turn_count}")
        print(f"Jobs:               {job_count}")
        print(f"Pending Actions:    {pending_count}")
        print(f"Resume Events:      {resume_count}")
        print(f"Assets:             {asset_count}")
        print(f"User Integrations:  {integration_count}")

        # Breakdown by conversation
        if user_id:
            result = await conn.execute(text(
                "SELECT id FROM conversations WHERE user_id = :uid"
            ), {"uid": user_id})
            convos = result.fetchall()
            print(f"\n--- User {user_id[:8]} ---\n")
            print(f"Conversations: {len(convos)}")

            for (cid,) in convos:
                result = await conn.execute(text(
                    "SELECT COUNT(*) FROM turns WHERE conversation_id = :cid"
                ), {"cid": cid})
                turn_count = result.scalar()

                result = await conn.execute(text(
                    "SELECT COUNT(*) FROM jobs WHERE conversation_id = :cid"
                ), {"cid": cid})
                job_count = result.scalar()

                print(f"  {str(cid)[:8]}: {turn_count} turns, {job_count} jobs")


async def main():
    parser = argparse.ArgumentParser(
        description="Query SpecTalk database tables.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )

    parser.add_argument(
        "table",
        choices=["users", "conversations", "jobs", "turns", "pending-actions", "resume-events", "assets", "summary"],
        help="Table to query"
    )
    parser.add_argument("--limit", type=int, default=None, help="Limit number of results")
    parser.add_argument("--user-id", type=str, help="Filter by user ID")
    parser.add_argument("--conversation-id", type=str, help="Filter by conversation ID")
    parser.add_argument("--status", type=str, help="Filter jobs by status")

    args = parser.parse_args()

    try:
        if args.table == "users":
            await get_users(limit=args.limit or 20)
        elif args.table == "conversations":
            await get_conversations(user_id=args.user_id, limit=args.limit or 20)
        elif args.table == "jobs":
            await get_jobs(conversation_id=args.conversation_id, status=args.status, limit=args.limit or 50)
        elif args.table == "turns":
            await get_turns(conversation_id=args.conversation_id, limit=args.limit or 20)
        elif args.table == "pending-actions":
            await get_pending_actions(conversation_id=args.conversation_id)
        elif args.table == "resume-events":
            await get_resume_events(conversation_id=args.conversation_id)
        elif args.table == "assets":
            await get_assets(user_id=args.user_id, conversation_id=args.conversation_id)
        elif args.table == "summary":
            await get_summary(user_id=args.user_id)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    asyncio.run(main())
