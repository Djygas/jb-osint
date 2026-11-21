# Telegram parser API status

This module supports TeleSINT/Telelog/Funstat key-based analysis. Current implementation is **best-effort** for Telelog/Funstat because public, stable endpoint specs were not fully verifiable from this environment.

## Confirmed in codebase
- **TeleSINT**: `https://api.telesint.dev/v1` via `Api-Key` header. Implemented endpoints: `/me/`, `/search/`, `/users/{id}`, `/groups/{id}`, `/groups/{id}/members`. See `app/src/main/java/ru/jbosint/osint/data/TeleSint.kt`.
- TeleSINT key check is free via `/me/`.

## Best-effort / needs confirmation
- **Telelog/Funstat**: `X-API-KEY` header, probing common bases:
  - Telelog: `https://telelog.info/api/v1`, `https://api.telelog.info/v1`, `https://telelog.info/api`
  - Funstat: `https://funstat.info/api/v1`, `https://api.funstat.info/v1`, `https://funstat.info/api`
- Candidate paths: `me/profile/user`, `user/users/search`, `chat/chats/search`, `chat/stats`, `message`, `forwards`, `contacts`, `sticker`.
- Facts from Telelog/Funstat are marked `apiConfirmed=false` with a note until endpoint contracts are confirmed.

## UI behavior
- Input modes: account/username/id, contacts/recent, sticker, group/channel, message link, forwarded group message, group stats, forwarded channel post.
- Unsupported/unconfirmed capabilities are surfaced as warnings/notes; the app does not invent data.
- Privacy/hide/auction status is informational and requires confirmed service API.

## Security
- Never log raw keys. Diagnostics use `DiagMask`.
- Network calls use HTTPS only.
