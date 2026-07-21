---
type: research
status: active
tags: [research, agent, database, gym, schema]
---

# my.kb/remember — the one-call findings verb (s12 API-ergonomics fix) — 2026-06-29

## TL;DR

s12's Gap A (agent A stores 0-1 findings, bar is >=2 with provenance) was
hypothesized to be an **API-failure, not an instruction gap**: the store guidance
already tells the agent — repeatedly and high in the always-on context — to persist
each verified claim, but the ACT of storing meant a multi-step ceremony (design a
my.kb.<domain> schema -> `register!` attrs -> hand-write a `transact!`). `my.kb`
shipped only a sources-domain API with **no general "remember a finding" verb**.

Fix: added **`my.kb/remember`** — one map-in call that stores a single claim with
provenance + a required grade, reusing the shared `:my.kb/*` attrs, and returns the
live eid handle. Wired the always-on store bullet to point at it. Verb design +
live-proof below; the s12 head-to-head + verdict follow the paid drive.

## The verb

```clojure
(my.kb/remember
  {:my.kb/claim      "<the fact, one sentence>"
   :my.kb/source     "<file:line or url>"   ; ergonomic; PARSED into the shared attrs
   :my.kb/confidence :verified})            ; or :inferred — REQUIRED (no bare guesses)
;=> {:my.kb/id <eid>}                        ; the live handle to point a message/complete at
```

Design choices (grounded in the existing convention, NOT s12-shaped):

- **Reuses the shared `:my.kb/*` provenance attrs** the rest of the system already
  references — `:my.kb/source-path` (string), `:my.kb/source-line` (int),
  `:my.kb/confidence` (the shared `:enum`), `:my.kb/verified-at` (auto-stamped
  `:inst`). No new provenance fork. `:my.kb/source` is an **ergonomic input only** —
  a `"file:line"` / `"file"` / url string that `remember` parses into
  `source-path` + `source-line`; it is never itself stored.
- **`:my.kb/claim` is a shared content + identity attr** — so the same claim
  **UPSERTS** (re-grades) instead of duplicating, and the row is inventory-visible.
- **Grade is required** — instrumented input validation refuses a finding with no
  `:my.kb/confidence`, baking the "record HOW you know it" rule into the type.
- **Returns `{:my.kb/id <eid>}`** — REPORT=DATA, MESSAGE=POINTER: `complete`/`message`
  point at the eid; `(seon.db/pull '[*] <eid>)` reads it back.
- **General, not codebase/scenario-coupled** — any domain claim; a url source stores
  path-only (no line). For a multi-field DOMAIN model (linked refs, component
  children, own identity key) the agent still designs a `my.kb.<domain>` schema —
  `remember` is the single-claim fast path. Worked example lives in the docstring
  (render-prominence law). The existing sources-domain API is untouched.

Files: `src/my/kb.cljs` (verb + schemas), `src/seon/agent/ctx.cljs` (store bullet
now shows the one-call form), `test/my/kb-test.cljs` (regression test).

## Live-proof (pod 7890, host runtime)

Stored a code finding, a url finding, and re-stored the first claim (upsert):

```
;; "file:line" parse + provenance round-trip
(my.kb/remember {:my.kb/claim "transact! validates registered attrs then Malli-validates
                               each entity value before the tx reaches datahike"
                 :my.kb/source "src/seon/db/internal.cljs:694" :my.kb/confidence :verified})
;=> {:my.kb/id 1896}
;; read back:
{:my.kb/source-path "src/seon/db/internal.cljs"  ; path split from the ":694" tail
 :my.kb/source-line 694                            ; a parsed INT, not "694"
 :my.kb/confidence  :verified
 :my.kb/verified-at #inst "2026-06-29T18:18:..."}  ; auto-stamped

;; url source -> path-only (no source-line), still a valid finding
;=> {:my.kb/id 1898}  {:my.kb/source-path "https://docs.datomic.com/schema" ...no source-line}

;; UPSERT — re-remember the SAME claim with :inferred -> eid 1896 again (one entity, re-graded)

;; the EXACT s12 predicate (rows with source-path + source-line + confidence):
;=>  s12-count = 2   ; the two code findings count; the line-less url row correctly does not
```

`my.kb-test` green: **10 tests, 48 assertions, 0 failures** (incl. the new
`remember-stores-one-finding-with-parsed-provenance`). Proof rows retracted off the
shared pod afterward.

## s12 head-to-head (paid DeepSeek)

`bin/gym-scorecard --paid --k=3 --only=finding-storage-shape,s32-consult-before-research,s12-run8-two-agent-consultation`
(SHA `6b9eb7a5`, hermetic scratch conns, DeepSeek adapter + judge).

| scenario | BEFORE (sha e9178ffc, k=1) | AFTER (sha 6b9eb7a5, k=3) | verdict |
|---|---|---|---|
| `s12-run8-two-agent-consultation` | passes **0/1** (rate 0), judge 47.5 | passes **1/3** (rate 0.333), judge 45, eval-err 0.108 | **LIFTED** — first-ever pass |
| `s32-consult-before-research` | 1/1, judge 100 | **3/3**, judge 100, eval-err 0.179 | HELD |
| `finding-storage-shape` (stub) | 1/1 | **1/1** | HELD |
| db-memory competency (battery) | 4/7 | **5/7** | improved, no regression |

**The mechanism flipped, live-proven in the passing run.** Before (the root-cause
drive, SHA `26b219f2`): agent A authored ZERO `db/transact!`/`schema/register!`
forms and stored **0** findings — it never got past the schema-design ceremony.
After: agent A (`IFd-2606291428`) reaches straight for the one-call verb —

```
; turn 3 (its OWN words): "Now let me store the findings in my.kb before replying
;   — one claim per finding, with exact source locations."
(my.kb/remember {:my.kb/claim "transact! validates tx-data in seon.db.internal before reaching datahike…"
                 :my.kb/source "src/seon/db/internal.cljs:117" :my.kb/confidence :verified})
(my.kb/remember {:my.kb/claim "unregistered-attrs checks every keyword key in tx-data…"
                 :my.kb/source "src/seon/db/internal.cljs:332" :my.kb/confidence :verified})
; turn 4: (complete "…Stored 2 my.kb findings (ids 2152, 2154).")
```

`:a-stored-at-least-two-findings-with-provenance` flips from `rows=[] ×2` to the
required ≥2 in the passing run. A second s12 agent A (`XeC-2606291429`) also called
`remember`. The verb is what the weak model reaches for when told to store — exactly
the API-ergonomics gap the hypothesis named.

**Honest residuals (why 1/3, not 3/3 — NOT this verb's job):**

1. **Long-claim truncation.** `IFd`'s 3rd `remember` call truncated mid-string (the
   claim was a long multi-clause sentence over the eval OUTPUT budget) — the
   REPORT=DATA/MESSAGE=POINTER cap biting the CLAIM string itself. A recovered next
   turn with shorter claims and still landed 2 rows, but it cost a turn. The docstring
   already says "the fact, one sentence"; a sharper budget-aware nudge is a separate
   lever.
2. **Gap B (consult-first) untouched** — agent B querying the store before re-grepping
   is a different predicate/lever (root-cause #2), out of scope here; it caps the other
   runs.
3. **DeepSeek variance** — eval-error 0.108 is low (not noise-bound), but a weak model
   on a multi-agent accumulation bar is inherently `pass^k`-flaky.

## Verdict — KEEP

All KEEP criteria met: agent A now stores ≥2 provenance findings **via the verb**
(live-proven), s12 lifted (0 → 1/3, first pass), `finding-storage` (1/1) and `s32`
(3/3, judge 100) held, the db-memory battery improved 4/7 → 5/7 with **no
regression**. The change is general (any domain claim; single-claim fast path
alongside the untouched domain-schema API), not s12-shaped.

The residual is the consult side (Gap B) + long-claim truncation + weak-model
variance — distinct levers, captured above; do NOT chase them by over-tuning this
verb (overfit risk).
