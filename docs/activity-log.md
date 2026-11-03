# Activity log (FR-29)

The desktop activity panel consumes the backend's `activityLogged` events and shows the most recent 100 entries for the current application session.

## Recorded events

- Local peer started and stopped.
- Peer connections established and disconnected.
- Shared-directory configuration changes.
- Downloads queued, resumed, completed, paused, cancelled, or failed.
- Errors surfaced by an application action or an unexpected Java backend failure.

Each backend event includes a UUID, an ISO-8601 timestamp, a category, a severity level, and a concise human-readable message. The renderer uses the event timestamp and ignores duplicate event IDs.

## Data minimization

The activity log records metadata needed to understand application behavior. It does not record file contents, transfer chunks, or absolute destination paths. Download entries may include the file name so the user can identify the affected task.

The current SRS requires an in-application activity view but does not require durable log retention. Entries are therefore session-scoped; **Clear visible** clears the current panel. This is not a deletion of transfer data or resume manifests.
