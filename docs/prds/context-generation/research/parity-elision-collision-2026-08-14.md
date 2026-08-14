---
type: research
status: current
tags: [research, print, render, archaeology, parity, elision]
---

# The parity/elision collision — how two cut regimes ended up in one printer

Design archaeology, 2026-08-14. Read end to end before writing:
[print-path-design-2026-08-01](../../sci-execution-runtime/plan/print-path-design-2026-08-01.md)
(the sealed REPL-parity emitter, ruling #26),
[value-printer-archaeology-2026-08-14](value-printer-archaeology-2026-08-14.md),
[one-renderer-prd-2026-08-14 §1](../plan/one-renderer-prd-2026-08-14.md).

This document reconstructs WHEN each regime entered, whether anyone
ever reconciled them, and lays out the reconciliation options with
their costs. **It does not recommend. The owner rules.**

## 0. The collision, stated precisely

`src/seon/print.cljc` today holds two mutually exclusive answers to
"what does a cut look like", selected by which options/entry point a
caller happens to use:

| | Parity regime | Elision-value regime |
|---|---|---|
| Cut trigger | `::length` / `::level` print options (`print.cljc:259-270`) | `fit` against a render profile (`print.cljc:908-943`) |
| Width cut face | bare `"..."` — `print.cljc:392, 453, 497` | `render-elision-ai` prose carrying omitted/total/path/offset/profile/requery — `print.cljc:283-304` |
| Depth cut face | bare `"#"` — `print.cljc:383, 430, 488, 542, 557` | `::elided` node with `:elision-unit :subtree` — `print.cljc:866-877` |
| Facts retained | none | count, known total, path, next offset, profile id, requery id or explicit refusal (`seon.print.edn:230-263`) |
| Authority | ruling #26, sealed design D5 row (`print-path-design…:540`) | AGENTS.md §2.4 + vocabulary row (`AGENTS.md:266, 440`) |
| Gate that exercises it | `test/seon/repl_parity_test.clj` | `test/seon/print_test.clj:246-268` |

Shipped defaults make the parity regime the DEFAULT everywhere `fit`
is not called: `:seon.print/length` default 32, `:seon.print/level`
default 8 (`resources/seon/schemas/seon.print.edn:31-38`), merged in by
`default-options` at every bare `emit-text` call site.

Live callers of the parity regime (bare cuts reach the agent):

- the transcript's stored-result line — `src/seon/render/transcript.clj:613-616`
  (`(merge (print/default-options) …)`);
- the agent's own eval face — `src/seon/sci/eval.clj:1030`;
- the MCP text envelope — `src/seon/cluster.clj:274-280` (passes
  `::length`/`::level` explicitly);
- the walked-value floor — `src/seon/render/value.clj:85-89, 372, 389-390, 430`.

Live callers of the elision regime: `src/seon/render.clj:517-532`
(`fit-terminal`), `src/seon/render/value.clj:504, 605`,
`src/seon/cluster.clj:378`.

**The regimes also compose incorrectly today.** `fit` deliberately
disables the bare cuts for its own convergence loop
(`options (assoc (default-options) ::length nil ::level nil)`,
`print.cljc:920`), but `fit-terminal` then emits the fitted node with
plain `(print/default-options)` (`render.clj:529`) — so a node whose
cuts were just turned into honest elision values is re-cut at 32
children / depth 8 with bare `"..."`/`"#"` on the way out. Neither
regime's guarantee survives that pair.

---

## Part 1 — git archaeology

`git log --follow src/seon/print.cljc`: 17 commits, `94220a629`
(2026-08-01) → `ab693ea4d` (2026-08-14).

### 1.1 The parity regime lands as ruled (94220a629, 2026-08-01)

`94220a629` "Implement sealed admitted print grammar" is the direct
implementation of ruling #26 — 444 new lines of `print.cljc`, the
`seon/schema/print.edn` node grammar, the admission grammar change, and
`test/seon/print_test.clj`. The elision faces are exactly the sealed
design's D5 row: `::elided` → `"..."`, `::pruned` → `"#"`, options
mirroring `*print-length*`/`*print-level*` (`print-path-design…:322-323,
337-346`). At this commit the printer has ONE cut regime and it is
stock-REPL-shaped.

`9acc78cd9`, `cbd6bd5a3`, `4161eed12` (all 2026-08-01) migrate the
render floors onto it and add the table face. No cut behavior changes.

### 1.2 The elision-value regime enters three days later (e34eea186, 2026-08-04)

**This is the commit where the collision was created**, and it is not on
the list of "elision commits" the task named — it predates them and
causes them.

`e34eea186` "feat(render): make generic projection walk structurally
total" (2026-08-04, +563/−140 across 7 files) does all of the following
in one commit:

1. Redefines `:seon.print/elided` in the schema from a bare
   `{::face ::elided}` map into the rich elision value — `::omitted`,
   `::elision-unit`, `:seon.render.data/total`, `/path`,
   `/next-offset`, `:seon.render.profile/id`, and an exclusive
   `::requery-id` XOR `::requery-refusal`
   (`git show e34eea186 -- resources/seon/schemas/seon.print.edn`).
2. Adds `render-elision-ai` and **overwrites the sealed D5 face**:
   `(defmethod emit ::elided … (-token sink ::elision "..."))` becomes
   `(-token sink ::elision (render-elision-ai node))`. The diff hunk is
   three lines; there is no comment, no note, and no reference to
   ruling #26 or to the sealed design document.
3. Introduces `fit` — the convergence loop that halves string limit,
   then children, then depth until `tokens/estimate` fits
   `:seon.render.profile/token-budget` — plus `fit-node`,
   `fit-children`, `fit-string`, `elision-node`, `enrich-elisions`, and
   the `:seon.render.profile` schema. `fit` sets `::length nil
   ::level nil`, i.e. it explicitly switches the parity regime OFF for
   its own emission. **This is where `fit` came from and what it claimed
   to solve: making the generic projection walk structurally total under
   a token budget** — a walk/context-budget problem, not a printing
   problem.
4. Adds the `::projected` face (a terminal render output embedded in
   the node tree) and `-fragment` on the `Sink` protocol.

Note the direction of travel: the ancestor of `elision-node` is not in
the printer at all. `git log -S'elision-node'` shows its first
appearance at `8fdedd29e` (2026-07-31, "Make walk context relevant and
compact") in `src/seon/render/walk.clj` — i.e. **the elision value is a
context-WALK construct that was pulled down into the printer** by
`e34eea186`, four days after the walk invented it and three days after
the printer was sealed with a different answer.

The same day's handoff records this as an output-floor work item, not a
print-path decision:
"`seon.print/fit` as the ONE fit owner (original window deleted), agent
profiles as config facts (1,024 tokens / depth 8 / 32 children),
elisions carry count+total+path+offset+profile+digest"
(`docs/prds/sci-execution-runtime/plan/handoff-2026-08-04-night.md:64-68`).

### 1.3 The named commits are all elision-regime hardening, none reconciling

| Commit | Date | What it changed | Regime |
|---|---|---|---|
| `aaaaf856b` | 08-04 | tests for nested identity + requeryable elision | elision |
| `e35e7b27f` | 08-04 | retain source totals across guarded projection | elision |
| `66679cf89` | 08-07 | cache option defaults (perf) | neither |
| `977f3a033` | 08-10 | CSS for print faces; `print.cljc` +4 lines only — closes `values-render-as-bare-triangles` | presentation |
| `3f6958fc2` | 08-12 | **drops `::address` from `::object`** and from `::failed`; makes `fit-children` carry a cut elision's total/requery through | BOTH — see below |
| `731958e80` | 08-12 | `structural-elision` preserves the carried total at depth cuts | elision |
| `38971ffdd` | 08-12 | `preserve-requery` keeps id XOR refusal exclusive | elision |
| `ff182e7e9` | 08-13 | runtime error class markers beside kind | neither |
| `4bc8104d8` | 08-12 | `emit-entry` stops emitting bare `"..."` for a cut MAP entry (comment: "Emitting a bare marker here discarded every one of those facts"); `emit :default` becomes total; **`fit-terminal` added to `render.clj:517-532`** | elision |
| `67956fa3f` | 08-14 | adds `bounded-text` + `::bound-by` so a text cut is an elision | elision |
| `ab693ea4d` | 08-14 | `bounded-text` deleted; two owner seams only — `fit` (profile bounds) and `admit-string` (storage caps); `seon.fn` report rejects a third direct caller | elision |

Two of these are worth reading closely.

**`3f6958fc2` is the only commit that ever touched the parity gate
because of a face change** — and it did so by *relaxing the gate to
match the code*, not the reverse. It removed `::address`
(`System/identityHashCode`) from the `::object` face, which the sealed
design had explicitly required and justified ("Capturing it at
admission makes the receipt's bytes deterministic forever — the same
transcript re-renders identically after a restart, which is exactly what
a stable prompt-cache prefix requires", `print-path-design…:262-266`).
The commit then rewrote `test/seon/repl_parity_test.clj` B10 from a
regex accepting `#object[clojure.lang.Atom 0x…]` to an equality on
`"#object[clojure.lang.Atom]"`, keeping the row `:passing` while the
bytes now diverge from stock (`git show 3f6958fc2 -- test/seon/repl_parity_test.clj`).
This is the one recorded instance of parity being consciously traded —
and it was traded for determinism, not for elisions, with no ruling
cited.

**`4bc8104d8` states the elision-regime rationale in a source comment**
— the closest thing to an explicit argument anywhere in the tree:

> An elision is ordinary data with count, path, profile, and requery
> evidence. Emitting a bare marker here discarded every one of those
> facts specifically when a map was cut.
> (`print.cljc`, `emit-entry`, added at `4bc8104d8`)

It changed exactly one of the eight bare-cut sites — the map-entry one —
and left the seven sequential/set/depth sites emitting bare markers.
That is the half-merge the one-renderer PRD inherited.

### 1.4 Reconstruction — was the parity regime ever reconciled?

**No.** The evidence:

- No commit message in the 17 mentions parity, ruling #26, or
  `print-path-design-2026-08-01.md`.
- The sealed design document has never been amended after Amendment 3
  (2026-08-01 night, `print-path-design…:570-598`); nothing in it
  mentions profiles, `fit`, requery, or elision values.
- `e34eea186` overwrote the D5 face with no note. The parity regime was
  **accreted over, not retired**: `::length`/`::level` still exist, still
  default to 32/8, and still fire at seven sites.
- The two regimes are exercised by DISJOINT test paths, which is why
  neither gate ever noticed. `repl_parity_test.clj:37-66` calls
  `print/emit-text` directly with `(:seon.print/options evaluation)` —
  it never calls `fit` and never constructs a profile, so it has never
  seen an elision value. `print_test.clj:246-268` calls `print/fit` with
  an explicit profile and asserts elision completeness — it never
  exercises the bare faces at a cut boundary.

The half-merge is therefore not a compromise anyone designed. It is two
correct-in-isolation designs that never met, because no test and no
document sits where they would have collided.

---

## Part 2 — ruling trace

### 2.1 The parity regime IS a numbered owner ruling

**Ruling #26 (owner, 2026-08-01)**, `plan/README.md:2493-2503`:
"THE PRINT PATH CONTRACT IS SEALED (`plan/print-path-design-2026-08-01.md`)."
The design it seals carries the D5 acceptance row verbatim:

> **D5** elision face | `::elided`→`...`, `::pruned`→`#`, with no
> appended annotation | `(range 200)` under `max-collection 64` ends
> `"… 63 ...)"` (`print-path-design…:540`)

and the ruling #25 companion at `README.md:2488-2490`: "the transcript
appends no 'N of M shown' notice; elision is visible in the actual
value, and fuller size data is obtained by an explicit query." The
sealed design's own §"Notes on the grammar" repeats it: "**No
result-level annotation follows the value.**" (`print-path-design…:253-258`).

`render-elision-ai`'s output is precisely the annotation those two
rulings forbid: `"… 137 more children of 200; requery by […] at path […]
offset 63 with :seon.render.profile/agent"` (`print.cljc:283-304`).

### 2.2 The elision-value regime has NO ruling number

Searched: `plan/README.md` for `elision|elided|truncat` returns six
hits, none a ruling establishing the elision value (`:805` overrules a
`seon.db` bespoke elision; `:1986` is a REFS design note; `:2488` and
`:2494-2503` are rulings #25/#26 — i.e. the PARITY side; `:2847`,
`:2856-2866` are MCP `get_value` drilling). `README.md` contains no
occurrence of "render profile" or "requery" at all.

Its actual provenance is documentation-side and same-day with the code:

- `82ba0e019` (2026-08-04 22:02, "docs: reconcile August 4 vocabulary")
  adds the vocabulary rows for **render profile** and **elision value**
  to `AGENTS.md`, six hours after `e34eea186` (19:27) shipped the code.
  Both rows cite the code as their authority; neither cites a ruling.
- `3b7094fa9` (2026-08-13, "Land the rewritten shared instruction
  authority") carries them into current `AGENTS.md:266` (§2.4: "omitted
  detail is an elision value — ordinary data carrying count, path, and
  requery identity — never bare truncation") and `AGENTS.md:440` (the
  vocabulary row).

So the law now cited as §2.4 is a **vocabulary reconciliation of an
implementation choice**, elevated to a design law in the AGENTS.md
rewrite nine days later. It is real and binding today; it simply never
went through the ruling ledger, which is why nobody was ever prompted to
check it against #26.

### 2.3 Did any ruling ever address the conflict?

**No ruling, anywhere, addresses it.** Nothing in `plan/README.md`
mentions `seon.print` after ruling #26 (`grep 'seon.print'` over
`plan/*.md` returns only #26's own text plus PRD prose). The two
downstream documents that DESCRIBE the elision regime treat it as
settled fact and never mention the parity design:

- `plan/repl-transcript-context-prd-2026-08-10.md:566` tabulates
  "Elision | `:seon.print/elided` with omitted count, total when known,
  path, next offset, profile, and requery identity/refusal";
- `plan/handoff-2026-08-04-night.md:64-68` (quoted above).

The one-renderer PRD §1 is the first document to state a rule that
decides between them — "The VALUE PRINTER (the floor) has NO budget:
its only job is quality … its elisions are STRUCTURAL PAGINATION for
readability (windows with cursors and requery identities), identical for
both projections" — and even that does not name ruling #26 or say what
happens to the parity gate.

---

## Part 3 — reconciliation options

Neutral. Every option below is buildable; they differ in what they cost
and who pays.

### What the parity gate actually constrains (measured)

This is the load-bearing fact for costing, and it is smaller than it
looks. `test/seon/repl_parity_test.clj` rows that depend on a cut face:

| Row | State | Expectation |
|---|---|---|
| B2 | `:known-divergence` | `*print-length*` × `(range 5)` → `"(0 1 2 ...)"` etc. (`:248-266`) |
| B3 | `:known-divergence` | `*print-length*` × vector → `"[0 1 2 ...]"` (`:268-286`) |
| B5 | `:known-divergence` | `*print-level*` → `"(0 (1 #))"` etc. (`:302-320`) |
| B6 | `:known-divergence` | the level×length matrix, 12 cases (`:322-346`) |
| F3 | `:known-divergence` | stored receipt → `"(0 1 2 ...)"` (`:769-776`) |
| F4 | `:known-divergence` | stored receipt → `"[:a #]"` (`:777-784`) |
| B4 | **`:passing`** | empty collections never elide: `"()"`, `"[]"` (`:288-300`) |

`defparity` with `:known-divergence` asserts `(is (false? passes?))`
(`:112-114`) — **the gate currently requires those six rows to NOT match
stock**, because the agent's `set!` print vars do not survive into the
next form (Lane 1's unlanded seam). The only `:passing` row touching
elision is B4, and B4 passes because an empty collection is never cut at
all (`visible-items`, `print.cljc:265-270`) — it is indifferent to which
face a cut would wear.

Consequence: **no currently-green parity row would go red if bare
`"..."`/`"#"` were deleted outright.** Six rows would need their
`:known-divergence` reason updated from "pending Lane 1" to a ruled
divergence, or be deleted. The parity harness also never calls `fit`
(`:37-66`), so it is blind to the elision regime by construction and
would stay blind under every option below.

### Option (a) — position-dependent faces: one value, two faces

The elision node stays the single representation everywhere. The TEXT
face it wears depends on position: in **REPL result position** it prints
stock bytes (`"..."` / `"#"`); everywhere else (HTML, debug, walked
context blocks, MCP) it prints the full elision face. Same node, same
facts, two renderings — exactly the `/ai`-vs-`/html` split the system
already has, extended with a text sub-face.

- **Regime bit lives in:** the emit OPTIONS (a `::result-position?`-style
  print option), threaded from the caller. Result position is already
  knowable at `transcript.clj:613-616` and `eval.clj:1030`.
- **Breaks:** the D5 promise of "no appended annotation" is honored in
  the result line and violated everywhere else — which is arguably the
  intent of both rulings, but it means the SAME value reads differently
  in the transcript and in the debug pane, so a byte-comparison between
  the two faces (a trust surface the PRD §1 explicitly promises:
  "character-faithful modulo whitespace and color") is no longer
  possible for cut values.
- **P-TEE risk:** the sealed design's structural property is that
  stripping tags from the hiccup sink equals the text sink's output
  (`print-path-design…:551-554`). Under (a) the tee necessarily emits two
  different token texts for one node — P-TEE must be restated as
  "agrees per face", or the tee sink must be told which face it is
  running.
- **Tests:** `print_test.clj:246-268` unchanged (it asserts node facts,
  not bytes). Parity rows unchanged. NEW: a regression proving the two
  faces come from the same node and carry the same facts.
- **Agent sees:** stock `"(0 1 2 ...)"` in result lines — no drill
  affordance, no count, no requery id, exactly the loss `4bc8104d8`'s
  comment named. To recover the omitted facts the agent must requery or
  open the debug/HTML face.
- **Deletes:** the `::length`/`::level` bare-cut path as a SEPARATE
  mechanism (the options become face selectors over the one elision
  node), which is the accretion the archaeology calls the half-merge.

### Option (b) — parity dropped: honest markers everywhere

One face, always the elision value. `::length`/`::level` bare emission
deleted at all eight sites; `repl_parity_test` rows B2/B3/B5/B6/F3/F4
re-stated as ruled divergences (or deleted); the printer stops claiming
byte-identity with `clj` for cut values.

- **Breaks:** ruling #26's D5 row and ruling #25's "no N of M notice"
  become dead letters and must be formally superseded in
  `plan/README.md`. The sealed design's P-TOTAL round-trip property
  (`print-path-design…:555-561`) already excludes `::elided`/`::pruned`
  from readback, so P-TOTAL survives unchanged.
- **Tests:** six parity rows re-stated; `print_test.clj:240, 255-269`
  (the rows the archaeology flags as stale) rewritten as the PRD §6
  properties; `repl_parity_test.clj:37-66`'s shim would need a profile
  to construct elisions at all, or its rows become "cut faces are not a
  parity subject".
- **Agent sees:** the archive's honest style, e.g. today's
  `render-elision-ai` output. The archaeology's finding stands as a
  caution: the CURRENT prose face is verbose (profile id, offset, and
  path on every cut) and was written for a floor, not for a result line;
  option (b) is a decision about the REGIME, and a separate decision
  about the face's wording is still open (the archive's `… +129 more`
  is the compact precedent, `value-printer-archaeology…:56-59`).
- **Cost that is easy to miss:** stock-parity is currently the *only*
  argument keeping `::length`/`::level` alive; deleting them means the
  MCP path (`cluster.clj:274-280`) and the transcript path
  (`transcript.clj:613-616`) must acquire a render profile, which is a
  wiring change in three namespaces, not a printer change.
- **Aligns with:** one-renderer PRD §1 as written ("elisions are
  STRUCTURAL PAGINATION … identical for both projections") and
  AGENTS.md §2.4 as written. It is the option that requires no new
  concept.

### Option (c) — parity confined to the transcript's result lines

Parity bytes survive in exactly one place: the result line of a REPL
transcript entry. Everything else — HTML, debug, walked blocks, MCP,
namespace pages — always renders the rich face. Mechanically identical
to (a) in output; the difference is WHERE the regime bit lives, and that
choice is the whole decision:

1. **In the render REQUEST** (`:seon.render/call-request`, `render.clj`).
   Pro: the request already carries the target profile and the output
   projection, so one more key is accretion; the caller that knows it is
   rendering a result line is the transcript renderer, and it already
   builds the request. Con: every non-request emit path
   (`eval.clj:1030`, `cluster.clj:274-280`, direct `emit-text` callers)
   is outside the request boundary and would default to one regime
   silently — the absence-as-health shape AGENTS.md warns about.
2. **In the render PROFILE** (`:seon.render.profile/*`, a config fact).
   Pro: database-derived, queryable, per-consumer — the existing
   mechanism for exactly this kind of policy, and `fit` already takes a
   profile. Con: a profile is selected per CONSUMER (agent / web /
   MCP), not per POSITION, so "result line" and "walked block" inside
   the SAME agent context would need two profiles — either a second
   profile selection inside one render, or a new position dimension on
   the profile schema. This is the option with the largest schema blast
   radius.
3. **In the FACE** (a distinct node face, e.g. `::elided` vs a
   parity-shaped sibling, chosen at cut time). Pro: no options
   threading at all; the tee sink cannot disagree because the node
   already says what it is; P-TEE survives verbatim. Con: it puts a
   PRESENTATION decision into the stored node — the node grammar is
   what `result-edn` persists (`print-path-design…:406-425`, decision
   1(c)), so a stored receipt would freeze its face and re-rendering an
   old entry into a different surface could not change it. That
   directly contradicts the sealed design's reason for keeping
   `result-edn` as data.

- **Breaks:** same as (a) plus one more — under (c) the debug page and
  the transcript are formally allowed to differ, so the PRD §1
  "character-faithful" debug promise needs an explicit carve-out for
  cut values.
- **Tests:** parity rows survive unchanged and keep meaning something
  (they are the only rows exercising the position that keeps parity);
  `print_test.clj` gains a regression that a non-result position never
  emits a bare marker; a new regression that the transcript's result
  line and the debug pane render the same node.
- **Agent sees:** stock bytes in the result it just produced; rich
  faces in every block of context around it. Two vocabularies in one
  prompt is the honest description of the outcome — which is either the
  point (results are a REPL, context is a system) or the defect (the
  agent must learn two cut languages), and that is the owner's call.

### Cross-cutting repairs every option needs

Independent of which regime wins, these are defects in the hybrid as it
stands and would be fixed in the same slice:

1. `fit-terminal` re-cuts a fitted node with bare defaults
   (`render.clj:529` passing `(print/default-options)` after
   `print.cljc:920` deliberately disabled them). Whichever regime is
   ruled, this pair is wrong today.
2. `::truncated-string` prints its ellipsis INSIDE the quoted string
   (`print.cljc:529-531`, `(-token sink ::string (pr-str (str (::value
   node) "…")))`), lying about
   the string's content in BOTH regimes — flagged independently at
   `value-printer-archaeology…:63-65`.
3. `3f6958fc2`'s `::address` removal is an unreconciled parity
   divergence with a design paragraph (`print-path-design…:262-266`)
   still asserting the opposite; it should be either restored or
   recorded as ruled.
4. The parity harness never calls `fit` (`repl_parity_test.clj:37-66`),
   so it reports health about a path half the system does not use —
   the "absence of signal read as health" class named in AGENTS.md.

Open printer questions this document feeds:
[open-questions-2026-08-14 Q10](../plan/open-questions-2026-08-14.md).
