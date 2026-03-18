"""Push notification service using Firebase Cloud Messaging (FCM).

Uses the Firebase Admin SDK Messaging API.
Device push tokens are stored in users.push_token, updated via
POST /notifications/device/register.
"""

import asyncio
import logging
import uuid

logger = logging.getLogger(__name__)


async def send_push_notification(
    push_token: str,
    title: str,
    body: str,
    conversation_id: str | None = None,
    data: dict[str, str] | None = None,
) -> bool:
    """Send an FCM push notification to a device.

    Args:
        push_token: FCM device registration token from users.push_token
        title: Notification title
        body: Notification body text
        conversation_id: If provided, included in data payload for deep-linking
        data: Optional additional key/value data (all values must be strings)

    Returns:
        True if the notification was sent successfully, False otherwise.
    """
    if not push_token:
        logger.debug("send_push_notification: no push_token, skipping")
        return False

    try:
        from firebase_admin import messaging
        from auth.firebase import get_firebase_app

        get_firebase_app()  # ensure initialized

        notification_data: dict[str, str] = {}
        if data:
            notification_data.update(data)
        if conversation_id:
            notification_data["conversation_id"] = conversation_id

        message = messaging.Message(
            notification=messaging.Notification(title=title, body=body),
            data=notification_data if notification_data else None,
            token=push_token,
            android=messaging.AndroidConfig(
                priority="high",
                notification=messaging.AndroidNotification(
                    channel_id="spectalk_jobs",
                    priority="high",
                ),
            ),
        )

        # messaging.send is synchronous — run in executor to avoid blocking event loop
        loop = asyncio.get_event_loop()
        response = await loop.run_in_executor(None, messaging.send, message)
        logger.info(f"FCM notification sent: {response}")
        return True

    except Exception as e:
        logger.error(f"Failed to send FCM notification: {e}")
        return False


async def get_user_push_token(user_id: str) -> str | None:
    """Look up a user's FCM push token from the database."""
    from db.database import AsyncSessionLocal
    from db.models import User
    from sqlalchemy import select

    try:
        async with AsyncSessionLocal() as session:
            result = await session.execute(
                select(User.push_token).where(User.id == uuid.UUID(user_id))
            )
            row = result.one_or_none()
            return row[0] if row else None
    except Exception as e:
        logger.error(f"Failed to get push token for user {user_id}: {e}")
        return None
