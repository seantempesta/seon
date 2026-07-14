---
type: issue
status: open
severity: blocker
tags: [issue, web, database, agent, flow, architecture]
---

# Implement browser-session navigation provenance

## Problem

The active web UI has no database-backed browser-tab session. Human messages do
not identify their originating tab, turns do not identify the exact human
message they answer, and root has no safe way to move one originating tab
without guessing or affecting another tab.

## Evidence

No active source registers `:seon.web.session/id`,
`:seon.web.session/user`, or `:seon.web.session/location`, and no
`seon.web.session` namespace exists. `sessionStorage` does not occur in active
source. `seon.web.datastar` mints only an ephemeral feed `view-id`; the agent
page's `$selected` and `$pinnedselection` are browser signals.

`seon.web.serve/handle-chat!` submits from, to, and content only.
`seon.agent.message` has no `:seon.agent.message/web-session`, and
`seon.agent.turn/with-turn!` has no `:seon.agent.turn/cause-message`. A run
stores only its opening cause, which cannot identify a later human message
absorbed by the same run.

The complete source-grounded transition and two-tab proof are in
[[root-workspace-session-source-audit-2026-07-14]].

## Owner

One `seon.web.session` model over `seon.db.id/allocate!`, the database-derived
reitit router, the existing Datastar feed, the one message write path, and the
turn assignment transition. Do not promote the ephemeral feed `view-id`, add a
selected-agent attr, or create a session registry atom.

## Acceptance

- Each browser tab validates or writer-allocates one `{id, user, location}`
  session under its exact database attachment before opening its feed.
- Equal location reconciliation writes nothing; reload/reconnect restores only
  that tab's valid location and explicit surface pin.
- Human message -> session and turn -> exact cause-message are queryable; a
  scheduled/internal turn carries neither by invention.
- Root selection moves only the originating valid tab. Missing session or
  target returns an explicit error envelope.
- Two-tab, deletion, reconnect, restart, and reset browser journeys pass with
  server-side feed evidence.
