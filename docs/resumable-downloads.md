# Persistent download recovery

## Files on disk

Each ranged download writes a temporary file named `<target>.part` and a sidecar manifest named `<target>.part.resume` in the selected destination directory. The manifest is versioned and contains the task ID, source filename, destination filename, expected byte size, expected whole-file SHA-256, range size, and a SHA-256 for each completed range.

The sidecar is written through a temporary file and atomic replacement. A range is marked complete only after its bytes have been forced to the partial file and the range hash has been calculated. A crash before the sidecar update causes that range to be fetched again; it does not cause the receiver to trust uncommitted bytes.

## Recovery

The user selects the previous destination folder. The backend rejects symbolic links and non-regular partial files, validates manifest fields and filename/path correspondence, then hashes each previously completed range. Corrupted or incomplete ranges are marked missing.

To resume, the user connects to one or more peers and selects the interrupted task. The backend requests current file metadata and requires an exact size and SHA-256 match with the saved manifest. Peer IDs are not persisted because a remote peer may have restarted and received a new ID.

Only missing ranges are downloaded. The completed file is accepted only after the expected byte count and final whole-file SHA-256 match. The final move does not replace an existing destination file. The manifest is removed after successful completion.

## Lifecycle

- Application shutdown pauses active downloads after persisting completed ranges.
- Explicit cancellation removes the partial file and manifest.
- Explicit local peer stop follows the existing stop lifecycle and cancels active transfers.
- Network/source interruption leaves a paused, recoverable task when a valid manifest has already been saved.
- Integrity failure and local file I/O failure do not produce a successful or resumable completion.
