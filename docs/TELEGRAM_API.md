# Telegram parser API status

This module supports TeleSINT/Telelog/Funstat key-based analysis. Current implementation is **best-effort** for Telelog/Funstat because public, stable endpoint specs were not fully verifiable from this environment.

## Confirmed in codebase
- **TeleSINT**: `https://api.telesint.dev/v1` via `Api-Key` header. Implemented endpoints: `/me/`, `/search/`, `/users/{id}`, `/groups/{id}`, `/groups/{id}/members`. See `app/src/main/java/ru/jbosint/osint/data/TeleSint.kt`.
- TeleSINT key check is free via `/me/`.

## Telelog/Funstat (confirmed spec)
- Swagger: `https://telelog.info/swagger/v1/swagger.json` (Funstat API v1).
- Auth: `Authorization: Bearer <JWT>`.
- Key check: `GET /api/v1/users/basic_info_by_id?id=<uid>` (uid from JWT) or `resolve_username`.
- Implemented endpoints: `users/resolve_username`, `users/basic_info_by_id`, `users/{id}/stats_min`, `users/{id}/stats`, `users/{id}/usernames`, `users/{id}/names`, `users/{id}/groups`, `groups/{id}`, `users/{id}/stickers`.
- Costs are returned in `tech.request_cost/current_ballance`; UI should treat requests as paid.
- Not confirmed/supported by public spec: contacts/recent import, message-by-link, channel post forwarders, privacy/hide/auction commands. These are surfaced as warnings.

## UI behavior
- Input modes: account/username/id, contacts/recent, sticker, group/channel, message link, forwarded group message, group stats, forwarded channel post.
- Unsupported/unconfirmed capabilities are surfaced as warnings/notes; the app does not invent data.
- Privacy/hide/auction status is informational and requires confirmed service API.

## Security
- Never log raw keys. Diagnostics use `DiagMask`.
- Network calls use HTTPS only.
