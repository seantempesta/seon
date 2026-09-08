---
type: research
status: draft — gates in progress
date: 2026-09-07
tags: [research, sci, admission, print, storage, repl]
---

# One streaming storage bound, and elision only at the AI boundary

Written by the `storage-bound` lane against
[the agent record and turn loop PRD](../plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§5 and §8 step 1, the
[astra review](prd-review-turn-loop-astra-2026-09-07.md) B8, the
[print and admission landing](print-and-admission-landing-2026-09-07.md), and
[the absent-cap issue](../../../seon/issues/absent-admission-cap-crashes-the-print-walk.md).

## 1. What changed

1. **`seon.sci.admit` applies no display caps.** `max-depth`,
   `max-collection`, `max-string` and the node budget are gone from the walk,
   with every mechanism they existed for: `take-node!`, `flag!`, `prune!`,
   `cut!`, `elide!`, `append-elision!`, `mark-map-cut!`, `remaining-count`,
   and `:seon.sci.admit/capped?`. A value is admitted whole.
2. **One bound replaces them, and it is STREAMING.**
   `:seon.config.eval.result/max-bytes` is enforced as the walk emits the
   value's EDN, under the evaluation's own SCI interrupt (astra B8: a byte
   count on a finished string is not an execution bound, because a lazy
   sequence can block before its first byte). The walk is now the ONE
   serializer — what it emitted is what is stored — so there is nothing
   downstream to agree with.
3. **A value is stored faithfully or it is missing.** Over the bound, or when
   the walk could only DESCRIBE the value (a bare `#object[…]` host
   reference, or a projection that threw), admission returns nothing but
   `:seon.eval/missing` (`:over-bound`, `:unserializable`, `:lost`) and, for
   the bound, `:seon.eval/size`. Both are accretions into the existing
   `seon.eval` family.
4. **Windowing is deleted.** `seon.cluster.run/settlement-result`,
   `result-blob-smaller?`, `result-window-page-size`,
   `seon.render.value/result-window-edn`,
   `seon.render.transcript/capped-result?`, and `restorable-node`'s
   two-arity size check are gone. `:seon.cluster.eval/result-size` is no
   longer written by the settlement seam.
5. **A missing value ablates its handle.** It stores no `result-edn`, so
   `bind-stored-results!` never reaches it by construction, `entity-emission`
   mints no `:seon.repl/handle`, the response carries no `:seon.repl/result`,
   and a later form naming the dead handle gets sci's ordinary
   unresolved-symbol error. `seon.repl/missing-text` is the ONE generator of
   the sentence, and it is data, never comment-shaped (ruling 45):
   `#:seon.eval{:missing :over-bound, :size 8388608}`.
6. **The walk's connection truncation moved to the render profile.**
   `seon.render.walk` read `:seon.config.eval.result/max-collection` — a
   STORAGE cap — to decide how many connections on one attribute the agent
   sees. It now reads `:seon.render.profile/max-children`, the presentation
   authority already carried on every render request and already part of the
   byte-identity qualification (same db, same commit, same profile). When the
   profile derivation refuses, the walk shows every connection the pull
   returned rather than cutting silently at a number nobody declared.
7. **The filed NPE class is dead by construction.** The cap it read no longer
   exists; the one bound that replaced it is validated at the seam and its
   absence is a flat refusal naming
   `:seon.config.eval.result/max-bytes` as its `:seon.error/diagnostic-member`.

## 2. The bound, and why this number

`:seon.config.eval.result/max-bytes 8388608` (8 MiB).

Measured on this tree, 2026-09-07 (`tmp/storage-lane/probe.clj`):

| value | emitted bytes |
|---|---|
| `(range 100000)` | **5,288,936** |
| `(range)` | stops at the bound; `:seon.eval/size 8388608`; interrupt-fn consulted 157,403 times |

One print node costs ~48 bytes per scalar element
(`#:seon.print{:face :seon.print/number, :value 0}` is 48 characters), which
is what makes the PRD's own proof value five megabytes. 8 MiB is that
measurement with 59% headroom, and it is 2048× the inline blob threshold
(4,096) — the same power-of-two family as the storage decision that already
lives beside it, so there is one provenance rather than two.

## 3. Deviations from the assignment, and why

### 3.1 The four display-cap CONFIG keys are still declared

The assignment says to delete `max-depth`, `max-collection`, `max-string`
and `max-nodes` from `config/default.edn` and the config schema. Admission no
longer reads any of them and they are gone from `:seon.sci.admit/caps`. They
are still DECLARED, because six owners outside this lane's paths read them
through `seon.config/result-caps` and none of those reads is nil-safe:

| protected reader | what it uses the key for | what it needs instead |
|---|---|---|
| `src/seon/cluster/message.clj:279-303` | the inbound message content length limit | its own `:seon.config.message/max-content` |
| `src/seon/render/web.clj:3207` | hands `max-string` to that same message check | the same key |
| `src/seon/eval/drive.clj:89` | the same message check from the drive harness | the same key |
| `src/seon/render/ns.clj:43-50` | the namespace page's QUERY-WORK bounds (`max-work`, `max-results`) | its own query-work keys (AGENTS.md §2.4 already says these are separate) |
| `src/seon/error.clj:354-357` | builds the fault evidence RENDER PROFILE out of admission caps | render-profile constants of its own |
| `src/seon/instrument.clj:142-145` | `contract-evidence-caps` narrows admission for contract evidence | now dead — `evidence-caps` narrows nothing admission reads |
| `script/seon/fresh_operator.clj:1613-1617` | `select-keys` of the four when applying instrumentation | must add `max-bytes` (see 3.2) |

Re-homing these is a separate, well-shaped chunk: four keys, four owners,
their schemas and their tests. Deleting them from config without doing that
work would hand each of those readers `nil` and crash them — the exact class
this lane was sent to end.

### 3.2 `script/seon/fresh_operator.clj` must gain the new key

That template hands `seon.instrument/apply!` a caps map built by
`select-keys` of the four deleted-from-admission keys. Without
`:seon.config.eval.result/max-bytes` in that list, contract-evidence
admission answers with the missing-bound refusal instead of evidence. It is a
one-line addition to a protected file and is NOT made here.

### 3.3 Blob storage above the threshold is not implemented for results

The assignment says a faithfully stored value is "inline under the blob
threshold, blob above it". Storing a result blob-only would make it
unreadable: **nothing in the render path reads a result blob back today** —
windowing existed precisely so that something inline always remained, and
`blob/get` needs a CONNECTION, which neither `seon.render.transcript/history`
(a database value) nor `seon.repl/entity-emission` (a pulled unit) has. So
this lane stores every faithful value inline and stages no result blob,
rather than shipping a silent absence.

What that costs: a large result is one large string datom (up to 8 MiB). The
2026-08-03 measurement in `config/default.edn` says inline is the FASTER
shape at the sizes measured; the risk is index footprint, not latency.

What it needs: a connection (or a blob reader) at the render unit, at which
point `settlement-result` can stage above the threshold again and both
projections resolve the digest. Filed as a finding for the turn lane, which
owns the pull selectors and the unit.

### 3.4 `:seon.sci.admit/capped?` readers in protected files

Deleting the key is safe: every protected reader is nil-safe and `nil` now
means what `false` meant (`src/seon/error.clj:489`, `src/seon/effect.clj:606`,
`src/seon/cluster.clj:311,380,440`, `script/seon/dev/mcp.clj:530`). Two
protected sites still WRITE the key into a literal map
(`src/seon/sci/kernel.clj:628`, `src/seon/cluster.clj:371`); those are dead
keys in open maps and are named here rather than edited.

### 3.5 Schema resources touched outside the owned list

Three schema resources had to accrete the two new facts or drop the deleted
one, because a fact that is not in an entity map is never INSTALLED:
`resources/seon/schemas/seon.cluster.eval.edn` (`:seon.eval/missing` and
`:seon.eval/size` added optional to the receipt and the settle request;
`result-size` left declared because `src/seon/cluster/loop.clj:711,754` still
writes it), `seon.sci.eval.edn` (evaluation and invocation result), and
`seon.repl.edn` (the emission). All three are pure accretion except the
removal of `:seon.sci.admit/capped?`, which no longer exists.

### 3.6 One protected call site had to change

`src/seon/cluster/loop.clj:1621` called `restorable-node`'s deleted two-arity
form. The edit is mechanical (drop the second argument) and the hook's static
analysis blocked publication until it was made.

## 4. Gates

(filled in below once the runs land)

## 5. Live verification

(filled in below)
