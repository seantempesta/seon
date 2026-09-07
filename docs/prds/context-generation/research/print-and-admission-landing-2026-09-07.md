---
type: research
status: complete
date: 2026-09-07
tags: [research, render, print, repl, sci, issues]
---

# Print, admission cuts, print-option carry, and one channel in a value

Written by the `print-and-admission` lane against the three filed issues
[object print node](../../../seon/issues/object-print-node-renders-as-empty-object.md),
[admission elision identity](../../../seon/issues/admission-elision-cannot-name-its-requery-identity.md),
[print-length carry](../../../seon/issues/a-set-of-print-length-does-not-survive-the-next-form.md),
and the run unit's ugly rendered value, under
[the agent record and REPL response PRD §4](../plan/agent-record-and-repl-response-prd-2026-09-07.md).

## 1. What changed

1. **`#object[]` never renders empty.** `seon.print`'s `emit ::object` reads
   the class the node carries under either `:seon.print/class` (what
   admission mints) or `:seon.print/name` (what a stored node may hold), and a
   node naming no class emits the flat `:seon.print/object-without-class`
   diagnostic. An empty rendering was the project's recurring class — absence
   reading as content.
2. **An admission cut carries count, path, and requery identity.**
   `seon.print/elision` is now the ONE public elision constructor (refitting's
   private `elision-node` delegates to it), and `seon.sci.admit`'s walk
   threads a path, so every cut records `:seon.render.data/path`,
   `/next-offset`, `:seon.print/omitted`, `/elision-unit`, and
   `:seon.render.data/total` when the source is `counted?`. The identity is
   handed in: `seon.sci.eval/evaluate` supplies the evaluation's own
   `[:seon.cluster.eval/id …]` as `:seon.print/requery-id` for both the
   settled and the failed admission. Nothing downstream repairs a cut it did
   not make.
3. **A `set!` of `*print-length*` survives the next form.** The turn's fork
   carries one print session; `fork-for-turn` seeds it from the agent's own
   latest evaluation that recorded one (derived, not remembered), and
   `evaluate`'s per-form `sci/binding` reads and writes that carrier instead
   of the process root.
4. **The channel in the run unit's rendered value is NOT a render defect** —
   see §4. Root cause found and filed; the one-line fix is in a protected
   file.

## 2. Measured before/after

Emitted with the shipped caps narrowed to `max-collection 2`:

```text
before: [0 … 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified]
after:  {:a [0 1 … 8 more children of 10; requery by [:seon.cluster.eval/id "[\"run\" 1]"] at path [:a] offset 2 with :seon.render.profile/unspecified], :b 1, :c 2}
after (no identity handed in): [0 1 … 8 more children of 10; requery refused: this admission was handed no requery identity at path [] offset 2 with :seon.render.profile/unspecified]
```

```text
before: (print/emit-text {:seon.print/face :seon.print/object :seon.print/name "clojure.lang.Atom"} …) => "#object[]"
after:  "#object[clojure.lang.Atom]"
```

## 3. Gates

Measured in a detached worktree at `4f8cd788f` (`tmp/print-lane-baseline`),
because the shared tree does not load at all right now: the `evaluation-merge`
lane's in-flight rename leaves `:seon.cluster.work/form-settlement` with a
duplicate `:seon.cluster.eval/id` key
(`resources/seon/schemas/seon.cluster.work.edn`), which fails
`schema/register!` with `:malli.core/duplicate-keys` and takes down `bin/test`
before any namespace runs, and the edit hook's `current-src` publication with
it.

### 3.1 The lane's gate, measured

`bin/test seon.print-test seon.sci.admit-test seon.sci.eval-test seon.repl-test
seon.cluster.run-test seon.render.transcript-test` in `tmp/print-lane-baseline`
(HEAD + this lane's four commits):

**147 tests / 931 assertions / 27 failures, 0 errors — 12 red tests.**

`seon.print-test`, `seon.sci.admit-test` and `seon.repl-test` are wholly green,
including the three new regressions.

Eleven of the twelve are inherited, measured rather than asserted: a second
detached worktree at the same commit WITHOUT this lane's changes
(`tmp/print-lane-headonly`, same selection) is red in exactly the same eleven,
each confirmed reproducible by the runner's own confirmation pass. Nine of
them are the documented inherited set
([the greens note §1.2](repl-grammar-greens-2026-09-07.md)): the eight
disabled-rendering-limits tests in `seon.render.transcript-test` and
`seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets`. The
two `seon.sci.eval-test` reds are inherited too but are NOT in that note's
list — they arrived with the in-flight evaluation-entity rename and should be
added to whatever ledger tracks it.

The three that are not in the greens note's list:

| test | attribution |
|---|---|
| `seon.sci.eval-test/runtime-function-rows-carry-parsed-contract-facts` | INHERITED — red at HEAD in the same selection, confirmed reproducible there |
| `seon.sci.eval-test/static-and-runtime-contracted-definitions-publish-identical-facts` | INHERITED — red at HEAD in the same selection, confirmed reproducible there |
| `seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt` | THIS LANE — and the fix is one line plus two stale expectations, §3.2 |

### 3.2 The transcript test this lane turned red, and why that is progress

`one-reply-reads-identically-…` (`test/seon/render/transcript_test.clj:1450-1461`)
asserts the DEFECT, deliberately and with a comment naming the issue note:

```clojure
(is (nil? (:seon.print/length follower))
    "the following form inherits nothing from the set!")
(is (str/includes? clipped ":value [0 1 2")
    "the value is printed under the shipped default, not the agent's choice")
```

Those are stale the moment the carry-forward lands (AGENTS.md §5, fixture rule
6: a test expecting the old lenient shape is stale, and the fix is the
expectation).

Its OTHER failing assertion, `page` ≠ `stored` at `:1434`, is a second real
defect this fix uncovered: `seon.render.transcript`'s `bounded-result` reads
`:seon.print/length` / `/level` from the entry, but `receipt-selector` — the
pull that builds the entry — **never asks for them**
(`src/seon/render/transcript.clj:46-64`). Every store-derived rendering
therefore printed under the shipped default while the in-memory one printed
under the agent's choice. It was invisible while no form ever carried a
stored length; now the two disagree.

The repair is therefore two changes, neither of them this lane's to make:
add `:seon.print/length` and `:seon.print/level` to `receipt-selector`, and
flip the two stale assertions to the ruled behaviour (`= 2`, `:value [0 1
...]`). Both were staged in the lane's throwaway worktree; the confirming run
did not finish before the lane closed (each `bin/test` invocation in this
environment spent longer preparing its shared published base than the whole
selection takes to run), so the repair is reported as READ-VERIFIED —
`receipt-selector` demonstrably omits the two attributes `bounded-result`
reads — and NOT as run-verified.
`src/seon/render/transcript.clj` outside the two "It did not run" sites, and
its test, belong to the `evaluation-merge` lane, so the change is reported
rather than made.


## 4. The channel in `It did not run:` — root cause, not a filter

The live page's run unit read:

```text
It did not run: {:seon.cluster.agent/id "juniper", :seon.cluster.run/id "db159431-…", :seon.render/context-channel #object[clojure.core.async.impl.channels.ManyToManyChannel 0x27342c75 …]} never arrived for :seon.render/context-acquisition within the declared :seon.config.eval/time-limit-ms bound of 30000 ms.
```

The value carried to the renderer IS the flat `:seon.error` diagnostic already.
What is wrong is the diagnostic's own member: `seon.render/acquire-context!`
passes a map of its inputs — including the live core.async channel — as
`:seon.error/diagnostic-member` (`src/seon/render.clj:1218-1231`).
`seon.await/diagnostic` prints the member into the message
(`src/seon/await.clj:46`), the loop stores that message as
`:seon.cluster.run/error` (`src/seon/cluster/loop.clj:723`), and both history
projections print it verbatim (`src/seon/render/transcript.clj:1313,1401`;
`src/seon/cluster/run.clj:2360`). The renderers are innocent, and filtering
`#object[` at one of them would hide the defect rather than end it.

The fix is one line — `:seon.error/diagnostic-member :seon.render/context-reply`,
the NAME of the awaited event; the agent and run ids are already in
`:seon.error/diagnostic-evidence`. `src/seon/render.clj` belongs to the
`evaluation-merge` lane, so this landed as
[a filed issue](../../../seon/issues/an-await-diagnostic-names-a-live-channel-as-its-member.md)
rather than an edit.

## 5. The live page, recorded

The dev cluster adopted this lane's commits
(`bin/seon --root tmp/juniper-context-live init --dev juniper-context` →
`:current-src commit 6a9f39e5-471f-541b-884d-4a42b769bd66`), and
`curl --max-time 8 'http://127.0.0.1:7766/feed/juniper?debug=true'` returned
200 / 228 282 bytes.

- **`#object[]`: 0 occurrences.** Every `#object[` on the page names a class.
- **`requery refused`: 0 occurrences.**
- The run unit still reads, verbatim:

```text
It did not run: {:seon.cluster.agent/id "juniper", :seon.cluster.run/id "db159431-4b68-4037-b5ee-3361cd80524b", :seon.render/context-channel #object[clojure.core.async.impl.channels.ManyToManyChannel 0x27342c75 "clojure.core.async.impl.channels.ManyToManyChannel@27342c75"]} never arrived for :seon.render/context-acquisition within the declared :seon.config.eval/time-limit-ms bound of 30000 ms. Nothing was retried, and nothing it asked for ran.
```

That is a stored `:seon.cluster.run/error` from a run that already closed, and
its cause is §4's one line in `src/seon/render.clj`. It will keep
rendering until that fact's producer is fixed; nothing at the renderer can
honestly remove it.

Live on the same adopted JVM (`mcp eval_clj`, `jvm` mode):

```clojure
(print/emit-text {:seon.print/face :seon.print/object
                  :seon.print/class "clojure.lang.Atom"} (print/default-options))
;; => "#object[clojure.lang.Atom]"

(print/emit-text (:seon.sci.admit/print-node (admit/admit-value {…max-collection 3…
                   :seon.print/requery-id [:seon.cluster.eval/id "[\"live\" 0]"]}))
                 (print/default-options))
;; => "[0 1 2 … 37 more children of 40; requery by [:seon.cluster.eval/id \"[\\\"live\\\" 0]\"] at path [] offset 3 with :seon.render.profile/unspecified]"
```

