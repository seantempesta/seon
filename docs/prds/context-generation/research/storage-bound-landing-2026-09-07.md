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
   sees. It now reads `:seon.render.profile/max-children` off the request the
   caller carried: the presentation authority, already part of the
   byte-identity qualification (same db, same commit, same profile). A
   request carrying no profile is not an AI context generation and elides
   nothing. Deriving one instead both cut where no presentation decision was
   made and re-read effective config per acquisition — three config reads
   where `public-walk-is-callable-through-an-agent-sci-eval` proves one.
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

### 3.3a What `:unserializable` costs, measured

The ruled line — a bare `#object[…]` at the ROOT of an admission stores
nothing — is wider than the channel the proof names. Every one of these is
now missing where it used to render as a description:

| value | before | after |
|---|---|---|
| `(async/chan)` | `#object[…ManyToManyChannel]` | `#:seon.eval{:missing :unserializable}` |
| `(atom 1)` | `#object[clojure.lang.Atom]` | the same marker |
| `(create-ns 'x)` | `#object[sci.lang.Namespace "x"]` | the same marker |
| `(in-ns 'x)` | the same | the same marker |
| a fn value | `#object[user/f]` | the same marker |
| sci's unbound marker | `{…/opaque "sci.impl.vars.SciUnbound"}` | the same marker |

None of them ever carried a restorable handle, so nothing that WAS reachable
became unreachable. What is lost is the class name, which was the whole
content of the old node — filed as
[a missing value loses the class it could not serialize](../../../seon/issues/a-missing-value-loses-the-class-it-could-not-serialize.md)
with the one-key accretion that would restore it. `clojure.lang.Var` was
moved onto the existing `:seon.print/var` face in the same wave, because a
Var IS its name in either world and admitting the host's as an opaque object
broke every `def` at the JVM REPL.

Four consequences the wave had to repair, every one a real defect the ruling
exposed and none of them findable by reading:

1. **Missing had to become a TERMINAL FACT.** An evaluation with no
   `result-edn` read as still running, so the turn's settlement refused with
   `::no-terminal-fact` and a form that had already executed would have been
   re-attempted. `seon.cluster.run/terminal?` and its two query twins
   (`seon.cluster.work/next-ordinal`, `append-generated-call`'s prefix check)
   now count `:seon.eval/missing`.
2. **`seon.sci.eval/evaluate` refused its own output.** The evaluation schema
   required `:seon.sci.admit/value`, which the ruled answer cannot carry.
   FOUND LIVE, not by reading: submitting `(range)` to the scratch cluster
   killed the turn before it settled, the agent's run never closed, and every
   later submission refused `agent-already-running`. Both evaluation shapes
   now declare value, result-edn, missing and size optional together.
3. **The fault committer died on a missing admission.**
   `seon.error/normalize` called `String.getBytes` on the absent bytes, so
   "A core fault could not be normalized" replaced the fault. One helper now
   re-admits the marker as itself. (`src/seon/error.clj` is outside the owned
   paths; this is a crash on the fault path and was repaired rather than
   filed.)
4. **`seon.render.value/artifact` had to become total.** It returned `{}` for
   a missing admission — a contract violation, and an empty panel where the
   reason belongs.

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

### 3.7 The `/data` route now serves a long string whole

`:seon.config.eval.result/max-string` was clipping strings at the `/data`
navigation surface as a side effect of being a STORAGE cap. With it gone,
a 5 MiB attribute is served in one response. That is the ruled behavior for
the value (HTML has no limits) and the test expectation moved to it, but the
route wants its own declared presentation window beside
`:seon.render.value/max-collection`:
[filed](../../../seon/issues/the-data-route-has-no-presentation-bound-for-a-string.md).

## 4. Gates

### 4.1 The inherited baseline, MEASURED

A detached worktree at `66cedc7fa` — this lane's parent, not the stale commit
the session banner named — with `reference-code` symlinked to the main tree's
submodules, same selection:

**17 red tests, inherited, before a line of this lane existed.**

```
seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets
seon.render.transcript-test/a-tight-budget-degrades-then-elides-loudly
seon.render.transcript-test/every-generated-history-is-ordered-total-and-token-bounded
seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable
seon.render.transcript-test/populated-history-restores-the-repl-fidelity-checklist
seon.render.transcript-test/receipt-content-enters-the-shared-capped-floor
seon.render.transcript-test/same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
seon.render.transcript-test/supersession-chains-vanish-before-token-accounting
seon.render.transcript-test/tight-budgets-pull-only-a-budget-derived-newest-candidate-set
seon.render.walk-test/one-basis-projection-covers-the-complete-walk
seon.render.web-test/a-fresh-cluster-debug-page-renders-a-prospective-prompt
seon.render.web-test/a-never-run-agents-debug-context-is-labeled-prospective
seon.render.web-test/an-unavailable-prospective-context-renders-its-diagnostic-data
seon.render.web-test/data-caps-a-five-megabyte-attribute-through-the-shared-floor
seon.render.web-test/the-message-appears-on-the-page-wire-test
seon.sci.eval-test/runtime-function-rows-carry-parsed-contract-facts
seon.sci.eval-test/static-and-runtime-contracted-definitions-publish-identical-facts
```

The eight `seon.render.transcript-test` entries plus
`settlement-mints-rows-for-unindexed-call-targets` are the documented
disabled-rendering-limits set
([the greens note §1.2](repl-grammar-greens-2026-09-07.md)); the two
`seon.sci.eval-test` contract rows are the pair that note said should be added
to a ledger; the five `seon.render.web-test` and `seon.render.walk-test` rows
had never been measured in that selection and are recorded here for the first
time.

`seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt`
is GREEN in that baseline, so it is this lane's to keep green.

## 5. Live verification

Scratch cluster `storage-lane` under `--root tmp/storage-lane-root`, reset and
republished onto this lane's commits, seeded with the Juniper fixture. Every
source below went through `seon.cluster.agent/submit-source!` — the ordinary
durable turn path — and its facts were read back out of the database.

| source | stored | `:seon.eval/missing` | `:seon.eval/size` | the REPL line the agent reads |
|---|---|---|---|---|
| `(range 100000)` | **5,288,936 bytes inline**, no blob | — | — | `#:seon.repl{:value (0 1 … 31 ...), :result result/e34279, :ns my.agents.juniper, :ms 115}` |
| `(range)` | nothing | `:over-bound` | **8388608** | `#:seon.repl{:value #:seon.eval{:missing :over-bound, :size 8388608}, :ns my.agents.juniper, :ms 116}` |
| `(atom 1)` | nothing | `:unserializable` | absent | `#:seon.repl{:value #:seon.eval{:missing :unserializable}, :ns my.agents.juniper, :ms 115}` |
| `(+ 1 1)` | 48 bytes | — | — | `#:seon.repl{:value 2, :result result/e34201, :ns my.agents.juniper, :ms 10}` |

Four things this proves, live:

1. **Faithful storage.** Reading the stored node back and deriving its value
   gives `{:count 100000, :first 0, :last 99999}` — the whole sequence, no
   window, no page.
2. **The bound stops an unbounded source at exactly the bound**, and the
   evaluation's own SCI interrupt is what the walk consults at every node
   (157,403 calls measured in the unit probe).
3. **A missing value ablates its handle.** Neither missing line carries
   `:result`. A later turn naming the dead handle gets sci's ordinary
   unresolved symbol, verbatim:

   ```text
   user=> (inc result/e34154)
   #:seon.repl{:error "Execution error (ExceptionInfo) at sci.impl.utils/throw-error-with-location (utils.cljc:67).
   Unable to resolve symbol: result/e34154", :result result/e34351, :ns my.agents.juniper, :ms 3}
   ```

   while the live handle beside it resolves: `(inc result/e34201)` → `2`… `3`.
4. **The AI boundary is where the elision is.** The 5 MiB value prints as
   `(0 1 … 31 ...)` because the session's `*print-length*` is 32 — a
   presentation decision on the stored value, not a storage cut. The stored
   bytes are all still there.

`seon.repl/render-html` shows the SAME elided line by construction: it is the
one REPL grammar the page, the history unit and the prompt share, and its
byte identity is a landed ruling. "HTML with no limits" is about the VALUE
surfaces (`/data`, `seon.render.value/render-html`), which now serve the whole
stored value — see §3.7.

## 6. The development cluster refused adoption — the orchestrator resets

`bin/seon --root tmp/juniper-context-live init --dev juniper-context` reached
`development JVM instrumentation` and then refused, verbatim:

```text
Development instrumentation configuration is unavailable.
{:seon.error/kind :seon.boot/refused
 :seon.boot/offense
 {:seon.config/missing-effective "juniper-context"
  :seon.error/kind :seon.config/missing-effective
  :seon.error/data #:seon.config{:missing [:seon.config.eval.result/max-bytes]}
  :seon.error/message "Effective configuration for cluster \"juniper-context\"
                       is missing required facts
                       [:seon.config.eval.result/max-bytes]."}}
```

This is the anticipated config-key refusal, only in the ADDITIVE direction:
the new bound is a required config fact and the running dev cluster's stored
configuration predates it. The publication itself succeeded — analysis,
schema, 30,402 program entities, every changed namespace reloaded — so the
branch advanced while the in-place adoption did not.

**The lane stopped there and touched nothing else in that root**, as the
assignment directs. The orchestrator's move is a reset (or a
`bin/seon --root tmp/juniper-context-live config apply juniper-context
config/default.edn` followed by a restart, since the config is arm-time).
