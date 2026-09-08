---
type: issue
status: superseded
severity: friction
tags: [issue, sci, repl, storage]
---

# A `:unserializable` value loses the one thing it could have said

## Problem

Since the storage-bound wave (2026-09-07), a value whose walk produced only a
description — a bare `#object[…]` host reference, or a projection that threw —
is stored as `:seon.eval/missing :unserializable` and nothing else
(`src/seon/sci/admit.clj`, `unserializable-root?`). That is the ruled shape: a
description of a value is not the value, and pretending otherwise is this
project's recurring "absence reads as content" class.

But the description carried ONE useful fact, and it is now dropped. Before:

```text
#:seon.repl{:value #object[clojure.core.async.impl.channels.ManyToManyChannel], :ms 3}
```

After:

```text
#:seon.repl{:value #:seon.eval{:missing :unserializable}, :ms 3}
```

The agent can no longer tell a channel from an atom from a file handle. A
refusal is supposed to NAME what was missing (AGENTS.md §2.4), and this one
names the category but not the subject.

## Reproduction

```clojure
(:seon.eval/missing (admit/admit {:seon.sci.admit/value (async/chan) …}))
;; => :unserializable, with no key naming clojure.core.async…ManyToManyChannel
```

## What to do

One accreted, declared fact on the evaluation — the class the walk met, e.g.
`:seon.eval/missing-class [:string …]` — written only for
`:seon.eval/missing :unserializable`, and rendered by `seon.repl/missing-text`
as `#:seon.eval{:missing :unserializable, :class "…ManyToManyChannel"}`. It is
an accretion (a new key, never a change to `:seon.eval/missing`), it stores
nothing that could be derived, and it restores the only information the old
opaque node had.

The storage-bound lane did not coin it, because coining a key outside a
bounded assignment is how vocabulary drifts; this note is the request.

## Superseded — owner ruling, 2026-09-08

The proposed missing-class serialization fact no longer belongs to the model. §15 keeps the actual object in the agent SCI context and stores the value renderer’s shown text, deleting the admission/serialization path this proposal would extend.

Authority: [agent record and turn loop PRD §15](../../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md#15-results-objects-in-memory-shown-text-on-disk-owner-2026-09-08).
This closes the old design proposal, not a claim that the protected turn and
render implementation has finished. No regression for the deleted design is
added. Implementation verification belongs to §12–§15’s live-object and
shown-text proofs.
