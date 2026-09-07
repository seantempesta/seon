---
type: research
status: complete
date: 2026-09-07
tags: [research, audit, repl, agent]
---

# Audit: is the REPL response and the agent record actually what we think?

The owner's instruction, verbatim:

> "Are you sure your agents are doing the right thing? Start asking socratic
> questions to confirm that things are being done as we think they are and that
> it makes sense."

Method: no lane report was trusted. Every claim below is either a `file:line`
read at the tree state named, or a read-only probe of the live development
cluster `juniper-context` (root `tmp/juniper-context-live`, pid 23438, JVM
started 2026-09-07T18:53:27Z) through `eval_clj` in `jvm` mode, plus two HTTP
reads of `http://127.0.0.1:7766`. Nothing was started, stopped, reset, or
transacted; the two page loads wrote no runs and no evaluations (§4).

**A tree-state warning that changes several verdicts.** `git status` was NOT
clean during this audit. Five files carry uncommitted edits — `src/seon/cluster/reply.clj`,
`test/seon/{bootstrap,cluster/reply,render/transcript}_test.clj`, and the PRD
itself — and `src/seon/repl.clj` changed on disk *during* the audit. The live
JVM is running that working tree, not HEAD. Every verdict below says which
state it is about.

---

## 1. Is there exactly ONE function producing REPL bytes?

**Verdict: does NOT hold. There are two live grammars, and the provider prompt
is on the wrong one.**

Every remaining formatter of a `ns=> ` prompt or of an evaluation result in
`src/` (grep for the literal arrow, excluding Malli `:=>`):

| Site | What it formats | Through `seon.repl/text`? |
|---|---|---|
| `src/seon/repl.clj:175` (`text`) | one evaluation | **is** the generator |
| `src/seon/repl.clj:248` (`render-html`) | the same evaluation, HTML | yes, calls `response` |
| `src/seon/render/transcript.clj:723`, `:728` (`input-text`, `receipt-text`) | the history unit's entries | **yes** |
| `src/seon/render/transcript.clj:1065` (`history-entries`) | `:seon.render.history/bytes` | **yes** |
| `src/seon/render/walk.clj:790` (`generic-history-entries`) | `(str ns "=> " (pr-str form) "\n" printed-value)` | **NO — second grammar** |
| `src/seon/render/transcript.clj:732` (`undisposed-run-text`) | `(str "system=> " (pr-str form) …)` | **NO — third, for the undisposed-run family** |

- **The provider prompt does NOT go through `seon.repl/text`.**
  `seon.render.web/context-pass` (`src/seon/render/web.clj:2609`, `:2648`) builds
  `:seon.cluster.prompt/text` from `history-text` (`:2546`) over
  `render.walk/history` entries, whose bytes are minted at `walk.clj:790`.
  `seon.cluster.prompt` merely carries that string (`src/seon/cluster/prompt.clj:184`).
- **The debug page's prospective prompt does not either** —
  `src/seon/render/web.clj:741` joins the same `walk` bytes.
- **The history unit does** — `src/seon/render/transcript.clj:1065`.
- **The page's AI column does** (through the transcript unit) — and that is
  precisely why the page is broken (§9/blocker 1).

The `evaluation-entity` landing note §4.2 states this honestly. But PRD §8's
verification "page AI column = history = prompt for the same evaluations" is
false today by construction, and worse: the prompt bytes an agent actually
reads pair a `pr-str` of the *re-read form* with a rendered value, so the
agent's context still shows the old grammar the whole change set exists to
delete. The nesting is worse than a duplicate: the transcript unit's
`repl/text` bytes become a `printed-value` that `walk.clj:790` then wraps in a
*second* prompt line.

---

## 2. Is the response map right?

Probes are `seon.repl/text` on hand-built emissions, live, in the cluster JVM.
Exact bytes returned:

```
my.agents.juniper=> (+ 1 41)
#:seon.repl{:value 42, :result result/e0, :ms 3}

my.agents.juniper=> (str "a\nb")
#:seon.repl{:value "a\nb", :result result/e1, :ms 0}

my.agents.juniper=> (do nil)
#:seon.repl{:value nil, :result result/e2}

my.agents.juniper=> {:a 1}
#:seon.repl{:value {:a 1}, :result result/e3}

my.agents.juniper=> (/ 1 0)
#:seon.repl{:error "Execution error () at (REPL:1).\nDivide by zero", :result result/e4, :ms 0}

my.agents.juniper=> (range 100)
#:seon.repl{:value [0 … 1 more subtree; requery refused: no stable identity was supplied at path [] offset 0 with :seon.render.profile/unspecified], :result result/e5}

my.agents.juniper=> (do (println "hi") 41)
#:seon.repl{:value 41, :result result/e6, :out "hi\n", :ms 1}

my.agents.juniper=> (in-ns 'my.tools)
#:seon.repl{:value #object[sci.lang.Namespace], :result result/e7, :ns my.tools, :ms 0}

my.agents.juniper=> (range)
```

What holds:

- **Key order** is the vector at `src/seon/repl.clj:41-47`, walked at `:143`,
  never a printed map. Holds.
- **Exactly one of `:value`/`:error`** — `src/seon/repl.clj:128` suppresses
  `:value` whenever an error is present. Holds.
- **`:out` escaping** — `pr-str`'d at `:139`, so `"hi\n"` stays on one line. Holds.
- **`:ns` only on change** — `:140`, `(not= ending-ns prompt-ns)`. Holds.
- **No `capped?` key.** Holds.
- **A string `:value` is quoted** — `print/emit-text` on the `:seon.print/string`
  face produced `"a\nb"`, escaped. Holds *for a stored node*. It does **not**
  hold for the path the transcript actually uses: `bounded-result`
  (`src/seon/render/transcript.clj:679-681`) returns the raw
  `:seon.print/value` for a string face, and `repl/value-text`'s `(string? node)`
  branch spliced it verbatim. That is the raw splice the audit asked about, and
  it is real (see blocker 1 — a lane is repairing it in the working tree right now).
- **A never-settled form** prints a prompt and no map. Holds.

What does **not** hold:

- **`:ms` is advertised and never emitted.** Live: **0 of 9** evaluations carry
  `:seon.eval/duration-ms`. Every `:ms` above came from a hand-built emission.
- **A clipped value emits a refusal sentence inside `:value`.** The `(range 100)`
  row above is not data; it is prose spliced into a data map, and it makes the
  response unreadable as EDN. PRD §4 promises "a clipped value carries its own
  elision node with a requery form (ruling 63c)"; what it actually carries is
  `requery refused: no stable identity was supplied`, because `value-text`
  calls `print/emit-text` with `(print/default-options)` and no elision root or
  profile (`src/seon/repl.clj:92`).
- **An error without triage reads `Execution error () at (REPL:1).`** —
  `src/seon/repl.clj:114` builds `{:clojure.error/phase :execution
  :clojure.error/cause error}` with no `:clojure.error/class`, so `main/ex-str`
  prints empty parens and invents a `(REPL:1)` location the evaluation never had.
- **`:seon.print/length` / `:seon.print/level` are stored on the evaluation**
  (`resources/seon/schemas/seon.cluster.eval.edn`, added this range) but
  `entity-emission` reads `:seon.print/options` (`src/seon/repl.clj:203`), which
  no stored evaluation carries. The per-form print options never reach the emitter.

**The parser (`seon.cluster.reply/sources`).** Probed live with the working
tree loaded, `(reply/sources text 'my.agents.juniper 500)`:

```clojure
;; "Here is the plan.\n; a note\n(def a 1)\n(inc a)\nThat is all."
[{:seon.cluster.run.form/source "(def a 1)"
  :seon.cluster.eval/comment "; Here is the plan.\n; a note"
  :seon.ns/name my.agents.juniper}
 {:seon.cluster.run.form/source "(inc a)"
  :seon.cluster.eval/comment "; That is all."
  :seon.ns/name my.agents.juniper}]
```

- The comment IS a separate fact and the source IS cut at the form's own
  offset — `form-start` (`src/seon/cluster/reply.clj:172`) used at `:257`,
  source `(subs source start end)` at `:262`. **Verdict: holds in the working
  tree, does NOT hold at HEAD.** `git diff -- src/seon/cluster/reply.clj` shows
  this repair is uncommitted; at HEAD `plan-sources` still binds `form-source`
  from the event's `::source`, whose span opens at the first comment, so the
  comment lands in the source and the comment fact is empty — exactly the root
  cause the landing note names.
- **Trailing prose is misattributed.** `"That is all."` was written *after*
  `(inc a)`; `plan-sources`' trailing arm (`src/seon/cluster/reply.clj:272-282`)
  appends it to that form's comment, and `repl/text` renders comments *above*
  the prompt (`src/seon/repl.clj:174`). The agent's own history therefore reads
  `; That is all.` above the form it followed — the rendered session inverts what
  the agent wrote.
- Live corroboration: 4 of 9 stored evaluations carry
  `:seon.cluster.eval/comment`, so the two-fact split is genuinely in the data.

---

## 3. Result handles

**Verdict: they collide, live, today. The PRD's fix is not implemented.**

`src/seon/repl.clj:138`: `(str "result/e" ordinal)`. Ordinals restart at 0 per
run. Live, agent `root`:

| run | ordinal | source | handle |
|---|---|---|---|
| `bootstrap:root` | 0 | `(help)` | `result/e0` |
| `bootstrap-supervision:root` | 0 | `(let [history (db/q …)] …)` | `result/e0` |
| `bootstrap:root` | 1 | `(dir my.message)` | `result/e1` |

Two different values under one name, both rendered into the same agent's
context. Juniper has three runs (`bootstrap:juniper`,
`273f7023-…`, `db159431-…`) and would repeat `result/e0..e3` per run once they
settle.

`fork-for-turn` **does** rebind handles from stored nodes:
`bind-stored-results!` (`src/seon/sci/eval.clj`, added by `741c1d6c3`) queries
`:seon.cluster.eval/result-edn` for **one run id** and calls `bind-result!` with
`admit/semantic-value`. It refuses for the faces in `opaque-result-faces`:
`:seon.print/{var,type,class,object,failed,throwable,truncated-string,elided,projected,pruned}`.
That refusal set is right and matches ruling 59c.

Two consequences the lane did not name:

1. Because the binding is scoped to the **current** run
   (`src/seon/cluster/loop.clj:1624`), a handle the agent *reads in its context*
   from an earlier run either resolves to nothing or — worse — resolves to a
   **different value** that happens to share the ordinal in the run now in
   effect. A silently wrong value is the worst available failure.
2. `restorable-result-node` consults only `result-edn`. It never checks
   `:seon.cluster.eval/result-size` or `result-blob`, so a windowed result
   whose root face is an ordinary `:seon.print/vector` binds a truncated
   collection as if complete. (No windowed rows exist on this cluster right
   now — 0 of 9 — so this is a code read, not a live observation.)

**The change needed.** The PRD now rules `result/e<entity-id>` (that amendment
is itself uncommitted: `git diff -- …agent-record-and-repl-response-prd-2026-09-07.md`).
Concretely: `response-entries` takes the handle from the evaluation's own
identity (`:db/id`, or the `:seon.cluster.eval/id` tuple) instead of `ordinal`,
and `bind-stored-results!` binds **every evaluation of the agent** that the
context can render, not one run's. Those two must land together — a
context-wide handle bound only per-run is the same bug with a longer name.

---

## 4. Previews

- **Is the AI preview an ordinary SCI evaluation in the assigned agent's fork
  with defs rehydrated?** Yes. `render-source-call`
  (`src/seon/render/web.clj:1517`) resolves the namespace through
  `assigned-agent-namespace` (`:1484`, `:1541` — assignment, not stewardship),
  calls `sci.eval/fork-for-turn` (`:1570`) with the agent id, then
  `loop/evaluate-sources` (`:1582`). Holds.
  Note it passes **no** `:seon.cluster.run/id`, so preview forks bind no stored
  handles at all.
- **Does it persist anything?** No runs and no evaluations. Counts before two
  page loads (`/ns/my.agents.juniper/debug` plus its SSE feed, twice):
  `{:runs 18 :evals 9 :forms 9 :t 536874280}`; after: `{:runs 18 :evals 9
  :forms 9 :t 536874290}`. **But `:t` advanced by ten** — those are committed
  render faults (§9). PRD §8's "ten loads write nothing" does not hold while the
  page is failing.
- **Is there a second evaluator / second cache?** Yes, all of it, unchanged —
  PRD step 3 has not started:
  - `render-source-call`'s private evaluation (E3) — `src/seon/render/web.clj:1517-1592`;
  - `reusable-evaluated-preview`, the third hand-written reuse predicate —
    `src/seon/render/web.clj:1491-1514`, still re-spelling the four checks that
    `render/same-invocation-evidence?` (`src/seon/render.clj:664`) already owns;
  - `current-read-evidence` — `src/seon/render/web.clj:2097`, still a duplicate
    of `render/refresh-read-evidence` (`src/seon/render.clj:673`);
  - `::calls` and `::invocations` still two stores of `:seon.render.call/output`
    (`src/seon/render/web.clj:2316`, `:2333`, `:2481`).
- **Is `evaluate-sources` still resolved through a config fact?** Yes.
  `src/seon/cluster/loop.clj:1557` is `(requiring-resolve
  (:seon.cluster.loop/evaluate cluster))`, and the value is set literally at
  `src/seon/cluster.clj:2498`. No `:seon.fn/calls` edge exists, so the census's
  own §5.3 regression — the one that closes the indirection — is still unwritten.

---

## 5. Stewardship

**Verdict: holds, on all four questions.**

- `:seon.ns/steward` is set inside the creation transaction —
  `[:db.fn/call #'steward-call agent-id namespace-name]`, which reads the
  mid-transaction database and asserts only when the namespace has none, so
  nothing displaces an existing steward. Live: both namespaces have one —
  `[["my.agents.root" "root"] ["my.agents.juniper" "juniper"]]`.
- **The agent's namespace attribute is non-unique in the LIVE schema.**
  `(get (:schema db) :seon.cluster.agent/namespace)` returns
  `{:db/ident :seon.cluster.agent/namespace, :db/valueType :db.type/ref,
  :db/cardinality :db.cardinality/one}` — no `:db/unique`. The refork the
  namespace-steward landing note said was required has happened;
  `:seon.ns/steward` is installed as a cardinality-one ref.
- **No code path requires a steward to evaluate in a namespace.** `steward-of`
  has exactly one caller in `src/`: `src/seon/render/ns.clj:417`, the
  "it belongs to agent X" line on a source-less namespace stub — presentation,
  not admission.
- **The page derives the evaluating agent from assignment.** `69b6a728f` added
  `cluster.agent/assigned-to` (`src/seon/cluster/agent.clj:335`) and both
  `ensure-namespace-owner!` (`src/seon/render/web.clj:3243`) and the debug route
  (`:3394`) now take `(first (assigned-to …))`.

One open item the landing note filed and this audit confirms is still open:
`seon.problems/unowned-namespaces` still inverts `:seon.cluster.agent/namespace`
to answer an ownership question that attribute no longer answers.

---

## 6. Projection binding

**Verdict: holds on both halves.**

- The turn: `src/seon/cluster/loop.clj:1965-1966` —
  `(if-let [projection-state (:seon.sci.eval/projection-state cluster)]
  (schema/call-with-projection-state projection-state pass) (pass))`, wrapping
  the whole `case` over open/call/generate/resume/close.
- The submission: `src/seon/cluster/agent.clj:741-744` — `submit-source!` wraps
  `submit-source-in-projection` in the same binding from the handle. This is
  option 1 of the projection landing note; it landed after that note was written,
  which is why the note still says "the submission path is not fixed".
- No unbound writer remains on the turn path. Every `db/transact!` in
  `src/seon/cluster/loop.clj` (`:670`, `:767`, `:818`, `:1052`, `:1226`, `:1366`,
  `:1416`, `:1788`, `:1799`, `:1865`, `:1887`) sits inside
  `settle-batch!`/`settle!`/`record-attempt!`/`{open,call,close,generate}-turn`,
  all reached through `pass`. `settle-interruption!` (`:1155`, `:1173`) is the
  boot recovery path, not the turn; `agent/armer-step` (`agent.clj:1162`) is a
  flow proc and depends on the `:io-exec` the projection note flagged as the
  fragile `cond->`.

---

## 7. Were test expectations weakened?

`git diff --stat 6a16fb60e^..HEAD -- test` touches six files.

| Test | Verdict |
|---|---|
| `seon.cluster.run-test/receipt-ai-is-only-repl-output` → `evaluation-ai-is-only-the-repl-session` | **WEAKENED.** `(is (= 2 (count (str/split-lines rendered))))` was **deleted**. That was the one assertion enforcing "a response is one line", and it is exactly the invariant the raw-string splice (blocker 1) violates. `starts-with?`/`ends-with?` → `includes?` is a defensible consequence of wrapping the text in a map; deleting the line count is not. The output-plus-value case (`"side effect\nnil"`) was also dropped, but it is covered by `seon.repl-test/printed-output-is-separate-from-the-value`, so that one is moved, not lost. Note the new expected bytes hard-code `:result result/e0`, so the suite now **locks in** the colliding ordinal handle (§3). |
| `seon.render.transcript-test` (committed hunk) | Not weakened — a `with-redefs` target renamed `run/render-receipt-ai` → `repl/response`. |
| `seon.render-coverage-test` | Not weakened — declared producers updated to `repl/render-ai|html`. |
| `seon.cluster.reply-test` | Not weakened; arguably strengthened — one glued-string assertion became two (source, then comment). |
| `seon.bootstrap-test` | Not weakened — `entry-source` → `(pr-str (:seon.repl/form next-entry))`, equivalent. |
| `seon.repl-test`, `seon.sci.eval-test` | New tests only. |

Uncommitted: `test/seon/render/transcript_test.clj` is being rewritten to the
new grammar right now (0 occurrences of `#:seon.repl{` at HEAD, 8 in the working
tree) — i.e. the 13 transcript reds the landing note §5b lists are **still red at
HEAD** and are being repaired in the working tree. `bin/test` was not run per the
assignment, so no tally is claimed here.

---

## 8. What contradicts the PRD or a ruling

- **Ruling 69 / PRD §4, the handle.** `result/e<ordinal>` contradicts the ruled
  `result/e<entity-id>`. Live collision proven (§3).
- **PRD §4, one generator.** Two grammars, and the prompt is on the other (§1).
- **PRD §4, the clipped value.** "carries its own elision node with a requery
  form" — it carries a requery *refusal* (§2).
- **PRD §8, "ten loads write nothing."** Ten fault commits observed (§4, §9).
- **Law 2.4, total renders.** `entity-emission`'s declared output requires
  `:seon.cluster.eval/source`, so `repl/render-ai` **throws** on a unit that
  lacks it — including a `:seon.error/value` map arriving where a unit was
  expected. Reproduced live: `seon.repl/entity-emission violated its contract
  (invalid-output): missing required key`. `render-ai` already has a
  `(when (seq source) …)` guard at `src/seon/repl.clj:228` that the contract
  never lets run.
- **Additions the PRD does not ask for:**
  - `:seon.repl/result-handle?` — a new boolean flag in the emission contract
    (`resources/seon/schemas/seon.repl.edn`, read at `src/seon/repl.clj:133`).
    Its only writer anywhere is `test/seon/repl_test.clj:143`. Ruling 59c says
    a handle that cannot be bound simply has none — which is derivable from the
    node's face, exactly as `opaque-result-faces` already derives it in
    `seon.sci.eval`. A caller-supplied flag re-remembers what the node answers.
  - `:seon.cluster.eval/author` — declared this range, **0 of 9** live
    evaluations carry it, and no writer exists in `src/`. PRD §6 deletes
    `:seon.cluster.work/situation` on the grounds that "authorship says it";
    authorship says nothing yet, and `:seon.cluster.work/situation` is still
    live (`src/seon/cluster/loop.clj:1932`).
- **Nothing in this range restores a `:seon.render/form` producer** (ruling 44);
  `run.form`'s projections were repointed at `seon.repl`. But
  `walk/history` still runs its `:seon.render/form` neighbourhood pass
  (`src/seon/render/walk.clj:820`), which the PRD deletes — unchanged, not regressed.

---

## 9. The render proc and the page hang

**Verdict: holds. A throwing page is now a visible section AND a committed
fault, and the proc survives.**

`render-pass` (`src/seon/render/web.clj:2373`) wraps each registration key's
`page-refresh` in `try`/`catch Throwable` (`:2404-2409`) and reduces on to the
next key. `failed-page-result` (`:2336`) builds the flat diagnostic, offers it
to the fault-committer channel (`:2358`), and returns a `seon-debug-body`
section in place of the page's content.

Proven live, without injecting anything — the mechanism is firing on its own:

```
GET /feed/juniper?…&prompt=false
event: datastar-patch-elements
data: elements <section class="seon-debug-body">
  <h2>This page could not be derived</h2>
  <pre>{:seon.render.web/page-derivation-failed true,
        :seon.error/message "Deriving this page threw clojure.lang.ExceptionInfo:
          seon.repl/text violated its contract (invalid-input): invalid dispatch value",
        …}</pre></section>
```

and the fault reached the database:

```clojure
[["seon.repl/text violated its contract (invalid-input): invalid dispatch value"
  "Mon Sep 07 13:02:32 CST 2026"] … six identical rows back to 13:01:31]
```

and `runtime_status` shows `seon.render.web/render` with `:ping "reply"` and
51 passes. All three halves hold.

Two caveats worth fixing:

- **The fault is committed once per pass with no dedup** — six identical faults
  in ninety seconds, the same overloaded-queue shape PRD §1 names. A repeating
  page failure should collapse by signature.
- `async/offer!` (`:2358`) drops the fault silently when the channel is full,
  and its result is unchecked — an absence-reads-as-health seam inside the
  machinery that exists to make failures visible. `failed-page-result` itself is
  outside the `try`, so a throw in it (e.g. a nil `:seon.cluster.agent/routing`)
  still ends the proc.

---

## Ranked defects

### Blocker

**B1. The debug page for every agent on the live dev cluster does not render,
and has not since 13:01.**
Evidence: the SSE feed above; six committed faults; `:t` advancing ten
transactions across two page loads that produced no evaluations.
Cause: `seon.render.transcript/emission` (`src/seon/render/transcript.clj:704`)
assocs `(bounded-result unit …)` — which returns **text** in every branch
(`:670-689`) — under `:seon.print/node`, whose declared schema is a print node.
`repl/text`'s contract rejects a string with `invalid dispatch value`, and the
throw escapes the whole page derivation. Reproduced in isolation:
`(repl/text {:seon.cluster.eval/source "(slurp \"x\")" :seon.print/node "a\nb"})`
→ the byte-identical refusal.
Smallest correct fix: the transcript hands its already-bounded text under its
own key (`:seon.repl/value`) rather than pretending text is a node, and
`value-text` prefers it. **A lane is applying exactly this in the working tree
right now** (`src/seon/repl.clj` changed mid-audit: `supplied :seon.repl/value`,
`(some? supplied) supplied`). It must be committed with a regression that
renders a *stored* evaluation end to end — `seon.repl-test` only exercises
hand-built emissions, which is why nine green tests coexisted with a dead page.

**B2. Result handles collide across runs, and a collided handle can resolve to
the wrong value.**
Evidence: `src/seon/repl.clj:138`; live, agent `root` renders `result/e0` for
both `bootstrap:root`/`(help)` and `bootstrap-supervision:root`/the supervision
`let`. `bind-stored-results!` binds one run's ordinals
(`src/seon/cluster/loop.clj:1624`), so the handle an agent reads from an earlier
run silently rebinds to this run's different value.
Smallest correct fix: derive `:seon.repl/result` from the evaluation's identity
(the PRD's own — currently uncommitted — `result/e<entity-id>` amendment) and
widen `bind-stored-results!` from one run to the evaluations the agent's context
renders. Both, in one commit; either alone leaves the bug.

**B3. The provider prompt is still on the second grammar.**
Evidence: `src/seon/render/walk.clj:790` → `web.clj:2546` → `web.clj:2609`.
PRD §8's "page bytes = prompt bytes" cannot be asserted, so the one end-to-end
regression the whole change set is for remains unwritable.
Smallest correct fix: the PRD's own design (landing note §4.2) — drop the
`/form` neighbourhood pass and let the `:seon.render/ai` pass alone carry the
bytes, replacing the `(seq? form)` admission gate with the fact gate (a unit
whose lookup names `:seon.cluster.eval/id`, plus the message arm).

### Friction

**F1. A clipped value emits a refusal sentence inside `:value`.**
`src/seon/repl.clj:92` calls `print/emit-text` with `(print/default-options)`
and no elision root or profile, producing
`[0 … 1 more subtree; requery refused: no stable identity was supplied …]`.
Fix: `value-text` receives the unit's elision root and render profile, or the
bounding caller supplies the already-emitted text (the same seam as B1).

**F2. An error without triage reads `Execution error () at (REPL:1).`**
`src/seon/repl.clj:114`. Empty class, invented location. Fix: carry
`:clojure.error/class` from the stored throwable class, and omit the location
clause rather than inventing `(REPL:1)`.

**F3. `:ms` is in the grammar and in no live response.** 0 of 9 evaluations
carry `:seon.eval/duration-ms`. Either settlement records it or the key leaves
the documented grammar; advertising a key nothing writes is a doc that lies.

**F4. Trailing prose is rendered above the form it followed.**
`src/seon/cluster/reply.clj:272-282` folds it into the preceding form's comment;
`src/seon/repl.clj:174` prints comments above the prompt. The rendered session
inverts the agent's authorship order. Fix: a trailing comment is its own fact
(or renders below the response), never above the prompt of an earlier form.

**F5. `repl/render-ai` throws instead of refusing.**
`entity-emission`'s declared output requires `:seon.cluster.eval/source`, so a
unit without one raises rather than returning nil, defeating the guard at
`src/seon/repl.clj:228`. Law 2.4: renders never throw. Fix: make the source key
optional in `:seon.repl/emission` and keep the existing guard.

**F6. The render fault storm has no dedup and an unchecked `offer!`.**
`src/seon/render/web.clj:2358`. Fix: collapse by diagnostic signature per page,
and treat a refused `offer!` as an observable event, not silence.

**F7. Uncommitted work is carrying three of this range's claims.**
`src/seon/cluster/reply.clj` (the span repair — without it the "comment is a
separate fact" claim is false at HEAD), `test/seon/render/transcript_test.clj`
(13 of the landing note's reds), and the PRD's own handle amendment. A lane's
heartbeat is commits; these need path-limited commits before anything builds on
them.

**F8. `seon.cluster.reply/sources`' 1-arity violates its own contract.**
`([text] (sources text nil (count text)))` passes `nil` into a
`:seon.ns/name` parameter declared `:qualified-symbol`; every call raises
`should be a symbol` under instrumentation. Inherited, not from this range, but
it silently blocks the simplest probe of the reader.

### Cleanup

**C1. `:seon.cluster.eval/author` is declared and never written** (0 of 9 live),
while `:seon.cluster.work/situation` — which PRD §6 deletes on the strength of
authorship — is still live at `src/seon/cluster/loop.clj:1932`.

**C2. `:seon.repl/result-handle?` is a flag with no production writer**
(only `test/seon/repl_test.clj:143`). The node's face already answers the
question, in `opaque-result-faces`.

**C3. `:seon.print/length` / `:seon.print/level` are stored on the evaluation;
the emitter reads `:seon.print/options`** (`src/seon/repl.clj:203`). Per-form
print options never reach the response.

**C4. `bind-stored-results!` ignores `result-blob` / `result-size`,** so a
windowed result can bind a truncated collection under a complete-looking handle.

**C5. The two-line invariant was deleted from `seon.cluster.run-test`** and the
new expected bytes hard-code the colliding `result/e0`. Restore a line-count (or
"the response is one line") assertion and re-express the handle assertion so it
does not fossilise B2.

**C6. PRD step 3 is entirely untouched:** `render-source-call`'s private
evaluation (`web.clj:1517`), `reusable-evaluated-preview` (`:1491`),
`current-read-evidence` (`:2097`), the twin `::calls`/`::invocations` stores,
and the config-resolved evaluator (`loop.clj:1557` ← `cluster.clj:2498`) all
survive, and the census's own two-assertion regression is unwritten.

**C7. `seon.problems/unowned-namespaces` still inverts
`:seon.cluster.agent/namespace`** to answer an ownership question that attribute
no longer answers (already filed by the namespace-steward lane).

---

## Answer to the owner

No — the agents did roughly the right *work*, but what they landed does not yet
do what we have been telling ourselves it does, and the cheapest proof is that
your development cluster's debug page has been dead since 13:01 while every
lane reported green tests. The one generator is real and its grammar is right on
the cases it was tested on, but it was only ever tested on hand-built emissions,
so the moment a *stored* evaluation flowed through it the transcript handed it
already-rendered text under a key declared to hold a print node, the contract
refused, the page derivation threw, and the render proc committed the same fault
every thirty seconds. That is the recurring disease in a new coat: nine green
tests measuring the shape of a value nobody in production produces. Two more
claims are softer than they sound — the provider prompt still goes through the
old `walk` formatter, so "one grammar" is true of the page and the history unit
and false of the thing the model actually reads; and `result/eN` is still keyed
on the per-run ordinal, which I proved collides live (agent `root` renders
`result/e0` for two different values), so an agent that reuses a handle it read
in its own context can silently get the wrong value — the ruled fix, handles
from the evaluation's identity, is written into the PRD but not into the code,
and the PRD edit is itself uncommitted along with the reply-span repair the
whole comment-as-a-fact claim depends on. What genuinely holds, and holds
cleanly: stewardship is a namespace fact decided inside the transaction, the
agent's namespace really is non-unique in the live schema, the page derives its
evaluating agent from assignment, the projection is bound around both the turn
and the submission, and the render proc now survives a throwing page with a
visible section and a committed fault — which is the only reason we could see
any of the rest.
