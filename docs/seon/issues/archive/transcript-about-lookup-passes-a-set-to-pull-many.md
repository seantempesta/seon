---
type: issue
status: resolved
severity: blocker
tags: [issue, render, database, class/n13, wave/live-drive-render]
---

# Pass ordered entity ids to transcript `pull-many`

## Problem

The transcript renderer constructs `about-eids` as a set and passes that set
to `seon.db/pull-many`, whose public contract rejects it. Rendering therefore
commits core faults; those faults create messages that cause another render
and repeat the same failure until recurrence suppression intervenes.

## Evidence

`seon.render.transcript/about-identities` builds:

```clojure
(into #{} (keep #(get-in % [:seon.cluster.message/about :db/id])) messages)
```

and then calls `(db/pull-many db selector about-eids)` without converting the
ids to the dependency's accepted ordered input.

The 2026-08-06 exact root prompt contains five identical outside messages:

```text
A renderer in my.agents.root failed. seon.db/pull-many violated its contract
(invalid-input) ... {:value #{23654 23659 23639 23649}, :message "invalid type"}
```

The corresponding 200 root HTML page contains the same pull-many contract
error fourteen times. This is user-visible error amplification, not only a
failed render call.

## Owner

`seon.render.transcript/about-identities`, preserving one transcript path and
the public `seon.db/pull-many` contract.

## Acceptance

- Transcript about-identity resolution passes one deterministic accepted
  collection of entity ids to `pull-many`.
- A message set containing several `:seon.cluster.message/about` refs renders
  once without a core fault.
- Re-rendering after the result commits does not create any new error message
  or transaction.

## Resolution (2026-09-15 triage)

surface: render-debug-page

Fix `9fa48fa20` replaced the rejected set construction. At HEAD `968a02c26`, `src/seon/render/transcript.clj:210–229` builds `(into [] (comp (keep ...) (distinct)) messages)` from `:seon.message/about` refs and passes that vector directly to `db/pull-many`. Verified the complete construction/call branch with `git show HEAD:src/seon/render/transcript.clj` and its history. The stated set-input contract failure cannot occur at this caller. No JVM or gate launched.
