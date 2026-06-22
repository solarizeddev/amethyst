# Notifications: local store + backward pagination

> **Status:** proposed (2026-06-22). Design doc for discussion, not yet
> implemented. Companion to the fetch-window fix in PR
> `claude/notif-fetch-window` (widening the inbox backfill beyond 7 days),
> which is the interim stopgap until this lands.

## Problem

Notifications are reconstructed from relays on every cold start:

- Events live only in memory (`LocalCache` → `LargeSoftCache`,
  `ConcurrentSkipListMap<K, WeakReference<V>>`). There is **no on-disk event
  store**, so nothing survives process death.
- The notification feed (`NotificationFeedFilter.feed()`) just scans
  `LocalCache`; it shows whatever was re-fetched this session.
- There is **no backward pagination** — the two notification fetchers
  (`AccountNotificationsEoseFrom{Inbox,Random}RelaysManager`) request a single
  window and rely on `limit`.

Consequences reported by users / maintainer:

1. Fresh install / cold start shows far fewer notifications than clients that
   query the same relays with a larger window (the stopgap PR addresses the
   7-day cliff, but is still `limit`-bounded and relay-dependent).
2. Notifications a relay has since pruned are gone forever — no local copy.
3. Loading "everything" up front is too expensive for big accounts (maintainer:
   "many minutes"), and persisting *all* events would blow up disk ("over 4GB
   easily").

## Goals

- **Instant** notifications on cold start (render from disk before the network
  answers; no empty/loading gap).
- **Retain** notifications even after relays drop them.
- **Bounded** disk usage with a clear eviction policy — store only what the
  notification feed needs, never the whole event graph.
- **Backward pagination** so older history loads on demand instead of in one
  expensive initial sweep.

## Non-goals

- A general-purpose on-disk event cache for all feeds. This store is
  **notifications-only**; the home/discover feeds keep their current model.
- Changing the Curated relevance rules (per maintainer, `tagsAnEventByUser`
  stays as-is; the broader view belongs to the true-Global feed).

## Design

### 1. Reuse the DM history-paging primitive (don't invent one)

The DM feed already solved "live tail + page backward, per relay, on demand"
— see `amethyst/plans/2026-06-01-dm-live-tail-and-history-slices.md`. We adopt
the same split for notifications:

- **Live tail** — the existing fixed recent window, open to the future, no
  `until`. New notifications arrive here. (This is what the inbox fetcher
  already does after the stopgap.)
- **History** — everything older than the live-tail floor, paged backward by
  `until`+`limit` **per relay, on demand**, driven by an on-screen "load older"
  marker at the bottom of the feed.

Reuse the existing primitives rather than copy them:

- `RelayLoadingCursors` (`requestedUntil` / `reachedUntil`, gap-proof
  "empty page + EOSE = nothing older", `limit`-not-exhaustion handling).
- `PerRelayLoadTracker` for the history layer; `WindowLoadTracker` for the
  live-tail boot barrier.

A new `AccountNotificationsHistoryEoseManager` mirrors
`AccountGiftWrapsHistoryEoseManager`, exposed via `dataSources()` as
`.account.notificationsHistory`, and the notifications screen triggers
`advance()` from a bottom marker the same way the DM rooms list does.

### 2. The notifications-only local store

A small persistent store holding **only the events behind notification cards**.

- **What to persist:** every event the notification feed accepts
  (`NotificationFeedFilter.acceptableEvent` == true) — reactions, reposts,
  zaps, replies, mentions, etc. — plus the minimal parents needed to render a
  card (the reacted/zapped/replied note and its author metadata). Not the whole
  thread, not unrelated events.
- **Format:** append-friendly NDJSON of raw signed events under
  `filesDir/accounts/<pubkey>/notifications/`, or a single Room table keyed by
  event id with `(created_at, kind)` indices. NDJSON keeps quartz as the single
  source of truth for (de)serialization and verification and avoids a schema
  migration surface; Room gives cheaper range queries for pagination. **Open
  question — pick one** (see below).
- **On cold start:** load the store into `LocalCache` (re-using the normal
  `LocalCache.justConsume`/verify path so signatures are still checked) *before*
  the first relay answer, then render. Relay results merge in as today.
- **On arrival:** when a live notification is accepted, write its event (and the
  minimal parent) through to the store.

### 3. Eviction — the 4 GB problem

Because we persist **only notification-relevant events**, the working set is a
tiny fraction of the full graph. Bound it further with a two-axis cap:

- **Count cap** — keep at most N notification events per account (e.g. 10k).
  This is the primary bound and maps directly to "newest N notifications".
- **Age cap** — drop anything older than M months (e.g. 6) regardless of count,
  so a quiet account doesn't keep ancient events forever.
- **Eviction policy — LRU by display, not by arrival.** Touch an event's
  recency when its card is rendered/scrolled into view, and evict least-recently
  *seen* first. Notifications the user keeps scrolling back to survive; one-off
  old ones age out. (Pure created_at eviction is the cheap fallback if tracking
  display recency proves noisy.)
- Eviction runs opportunistically (on write past the cap / on app idle), not on
  a timer.

Parents pinned only to support a still-present notification are evicted with
their last referencing notification (refcount or a sweep).

## Module placement

- The store implementation is **Android-side first** (`amethyst/`), next to
  `LocalCache` and the account file stores (`model/accountsCache/`,
  `model/preferences/`), since it depends on `LocalCache` and `filesDir`.
- File I/O is abstracted behind an interface so a future KMP/desktop actual can
  back it differently; the load/evict policy is platform-agnostic and could move
  to `commons` later if a second front end needs it. (Follow `commons/ARCHITECTURE.md`.)
- The history EOSE manager lives beside the existing notification managers in
  `service/relayClient/reqCommand/account/nip01Notifications/`.

## Phasing

1. **History pagination** (no disk): add `AccountNotificationsHistoryEoseManager`
   + bottom "load older" marker, reusing `RelayLoadingCursors`. Delivers
   on-demand older history and removes pressure to widen the initial window.
2. **Local store read path:** persist accepted notifications + minimal parents;
   load into `LocalCache` on cold start for instant render.
3. **Eviction:** count + age caps, LRU-by-display.
4. **Tune & measure:** caps, cold-start render time, disk footprint on real
   accounts.

Phase 1 is independently shippable and the natural follow-up to the stopgap PR.

## Open questions

- **Storage backend:** NDJSON of raw events (simple, quartz owns parsing,
  no migrations) vs Room (cheaper `created_at` range queries for pagination).
- **Verification cost on load:** re-verify signatures on every cold-start load,
  or trust the store as already-verified (it only ever held events we verified)?
  Trusting it removes the main cold-start CPU cost; threat model is local-disk
  tampering only.
- **Per-account vs shared store** for multi-account installs (lean per-account,
  matching the existing `accounts/<pubkey>/` layout).
- **Eviction recency source:** display-LRU (better UX, more bookkeeping) vs
  created_at (trivial). Start with created_at, upgrade if needed.

## Verification

- JVM unit tests for the store (write/read round-trip, count+age eviction,
  parent refcounting) — these run without the Android SDK if the store logic is
  kept free of framework types behind the file-I/O interface.
- Reuse `RelayLoadingCursorsTest` coverage for the pagination layer.
- On-device: cold-start render latency, disk footprint, and notification parity
  vs other clients on the same relays.
