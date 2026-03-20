# Database Query Scripts

Utility scripts for querying and inspecting the SpecTalk database.

## Usage

### View Database Summary
```bash
uv run python scripts/db_query.py summary
```

Get total counts for all tables and breakdown by user/conversation.

### Query Tables

#### Users
```bash
# List all users (limit 20)
uv run python scripts/db_query.py users

# Limit results
uv run python scripts/db_query.py users --limit 50
```

#### Conversations
```bash
# List all conversations
uv run python scripts/db_query.py conversations

# Filter by user
uv run python scripts/db_query.py conversations --user-id <UUID>

# Limit results
uv run python scripts/db_query.py conversations --limit 50
```

#### Jobs
```bash
# List all jobs
uv run python scripts/db_query.py jobs

# Filter by conversation
uv run python scripts/db_query.py jobs --conversation-id <UUID>

# Filter by status (queued, running, completed, failed)
uv run python scripts/db_query.py jobs --status completed

# Combine filters
uv run python scripts/db_query.py jobs --conversation-id <UUID> --status running
```

#### Turns (conversation messages)
```bash
# List all turns
uv run python scripts/db_query.py turns

# For a specific conversation
uv run python scripts/db_query.py turns --conversation-id <UUID>

# Limit results
uv run python scripts/db_query.py turns --limit 100
```

#### Pending Actions
```bash
# List all pending actions
uv run python scripts/db_query.py pending-actions

# For a specific conversation
uv run python scripts/db_query.py pending-actions --conversation-id <UUID>
```

#### Resume Events
```bash
# List all resume events
uv run python scripts/db_query.py resume-events

# For a specific conversation
uv run python scripts/db_query.py resume-events --conversation-id <UUID>
```

#### Assets
```bash
# List all assets
uv run python scripts/db_query.py assets

# Filter by user
uv run python scripts/db_query.py assets --user-id <UUID>

# Filter by conversation
uv run python scripts/db_query.py assets --conversation-id <UUID>
```

## Examples

### Get user summary
```bash
uv run python scripts/db_query.py summary --user-id 4cab6ffa-57ca-4f1b-b162-ce3118803f01
```

### See conversation turns
```bash
uv run python scripts/db_query.py turns --conversation-id 27ca1533-fd83-458a-b174-268ce4bd4abe --limit 30
```

### Check failed jobs
```bash
uv run python scripts/db_query.py jobs --status failed
```

## Notes

- UUIDs can be partial (e.g., just the first 8 characters) in output display
- All times are shown in `YYYY-MM-DD HH:MM` format (UTC)
- Text fields are truncated to 50 characters in list views
- Use `--limit` to control result count (defaults: 20 for most, 50 for jobs)
