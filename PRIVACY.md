# TeleDrive privacy and security notes

TeleDrive is a client for the user's own Telegram account. It does not provide a separate cloud service, analytics backend, bot, Telegram Web session, or server-side copy of files.

## What leaves the device

After the user completes Telegram's official TDLib authorization, TeleDrive sends file operations to Telegram using the Telegram API and the account's Saved Messages chat:

- Saved Messages history is read to build the local file index.
- Files selected through Android's Storage Access Framework are uploaded as Telegram documents.
- A requested file is downloaded from Telegram through TDLib.
- A confirmed remote deletion calls Telegram's message deletion API.
- When enabled, virtual-folder and favorite changes are appended as reserved text messages in Saved Messages.

TeleDrive does not send file contents, filenames, account identifiers, or usage analytics to a TeleDrive-operated server. Telegram's own terms, retention, account controls, and cloud-data policies apply to data sent to Telegram.

## What is stored on the device

- TDLib stores the Telegram session, message knowledge, and its managed file cache in app-private directories.
- The TDLib database key is protected with Android Keystore. TeleDrive does not persist authentication codes or two-step passwords.
- Room stores normalized file metadata, virtual folders, favorites, transfer state, and local cached-file paths.
- DataStore stores preferences, a random metadata device identifier, and a local metadata sequence number. The device identifier is not a Telegram identifier.
- Temporary upload staging files remain app-private. A successful or explicitly canceled upload removes its staging file; a retryable failure retains it so a durable retry can continue after process death.

No broad external-storage permission is required. The user chooses files and export destinations through Android's Storage Access Framework. Persistable read permission is requested only when Android offers it for a selected document.

## Deletion and cache boundaries

- **Remove local copy** deletes the tracked local cached file and clears its Room availability. It does not call Telegram and does not delete a Saved Messages message.
- **Clear local cache** removes tracked local copies on this device only. Files may be downloaded again.
- **Delete remotely** is a separate, explicitly confirmed action. It affects the actual Saved Messages message and is not required to clear local storage.
- Logging out closes the TDLib session and leaves Telegram messages untouched. The app does not claim that logout erases every TDLib cache file.

A file opened or shared with another Android app is exposed using a temporary `FileProvider` content URI with read permission. TeleDrive does not expose a raw private path.

## Metadata synchronization limitations

Telegram does not expose ordinary filesystem folders for Saved Messages. TeleDrive's folders and favorites are therefore an append-only event log in reserved text messages. Records are versioned and encoded, and are ignored by the file index. Metadata synchronization is eventual and best-effort:

- it is not an atomic multi-device database;
- concurrent changes are resolved by deterministic event ordering rather than user intent;
- deleting or losing metadata messages can make folder state unavailable;
- malformed or unknown records are ignored rather than applied;
- disabling the setting stops new writes and metadata reads for that device, but does not remove existing Telegram messages.

The UI must not represent these virtual folders as Telegram-native folders or promise instant cross-device consistency.

## Security limitations and user responsibilities

Ordinary Saved Messages are Telegram cloud data. TeleDrive does not claim end-to-end encryption, a guaranteed storage quota, unlimited file size, or protection from Telegram account/device compromise. Users should enable Telegram's account security features, review active sessions, and avoid storing content in Saved Messages that they do not want in Telegram cloud storage.

TDLib native libraries and the Telegram API credentials are supplied by the developer's local build configuration. API IDs, API hashes, TDLib databases, authentication data, personal Telegram data, and private file contents must never be committed to Git or shared in bug reports.

## Permissions and diagnostics

The app declares network access and network-state access for Telegram and transfer scheduling. It does not include advertising, crash-reporting, or telemetry SDKs in this repository. Errors shown by TeleDrive are sanitized gateway messages; authentication codes, passwords, and raw TDLib request payloads are not logged.

For a security issue, do not attach a TDLib database, local cache, Telegram export, screenshot containing account data, or a credential. Reproduce with the deterministic fake gateway where possible and report the minimum non-sensitive details.
