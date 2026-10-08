# Context, storage and pass-budget cards (2026-10-08; context track re-cut by lane context-cards)

Implementation cards for the context cut CX-1 … CX-13 (which replace S4-02a … S4-07b3, CS-03a, CS-03b and DBG-01),
SR-C/E/F/H/I, PB-03, PB-04 and BLOB-K. Cards name functions by name; line numbers drift, so delete and change by name.

## The context cut: target and evidence

Target (agent-loop.md §3.4, §3.5): the system message is `(help)`'s shown text; the history is the agent's own model
exchanges (its forms and the replies it got, each pass's test step included); the current-state block comes last; a
model turn runs only with an assignment, and stop or continue is derived from the reply. No system turn, no injected
forms, no opening, no prompt selection, capture or compaction.

Evidence: the first live deepseek-flash run (agent `live-flash`, branch `:agent-2246f5f5491f`, 2026-10-08; 19 stored
prompts read): one user message, no system message, no block, a `turns left: N of 5` tail; the first run's prompt grew
2.9 K → 140 K characters in ten passes; 7 of 16 call rows in the history were system or outside rows (identity,
steps, settings pulls, `seon.error/for-agent`, `:seon.repl/changes` diffs, root's MCP `settings!`); the model read its
own forms as someone else's and advised itself; it learned its proof was red only by evaluating the block itself.

## Order (each card about 15 minutes; group B is `turn.clj`, so B cards run in series)

State at HEAD `6d3c22dbb` (ledger §2): CX-1 (`ade6e3059`) and CX-2 (`7e39aeed3`) landed; CX-3, CX-6, CX-7, CX-8 and
CX-9 landed in one rip (`55ede54a9`), CX-10 in `94df1f74a` and `0de932400`, both resets done. Left: CX-4, CX-5 (its
card's `seon.cluster.prompt/window-check` moves to `seon.context`: the namespace is deleted), CX-11, CX-12, CX-13.
Run 3 found two defects in landed rows: CX-2 reads the assignment on the agent's branch instead of the task home, and
the system message is the help map printed unrendered (ledger §5 step 1).

1. **CX-1** the model sees help, its own exchanges, and its block last (first; the next live run's main change).
2. **CX-2** a model turn only with an assignment; a reply with no form waits.
3. **CX-3** no system turn before a model pass (and no stored read evidence written).
4. **CX-4** the transcript shows forms, not a prompt the model copies (Q-CX1).
5. **CX-5** a context over the model's window refuses before the call.
6. **CX-6** delete the opening run (6a) and the demo seed (6b). RESET NEEDED after 6a.
7. **CX-7** one debug view: the context as sent, from the one assembler.
8. **CX-8** delete the system turn, virtual turns, compaction and the web context editor.
9. **CX-9** delete prompt selection, its calibration and the turns frame.
10. **CX-10** delete capture, compaction, history selection and stored read evidence (10a, 10b). RESET NEEDED.
11. **CX-11** the model's history shows each pass's test step (after TX-0).
12. **CX-12** wire formats: the model's own forms as its own messages (Q-CX2).
13. **CX-13** history starts at the current assignment.

CX-4, CX-5, CX-7, CX-12 are file-disjoint from the B chain and may run beside it once CX-1 lands. S4-01 (agent
ordinal) leaves the context track: history keeps `seon.eval/of-agent`'s order (call row transaction, then ordinal),
which is writer order; S4-01 stays only as TX-8c's dependency. The proc-held history of S4-04 is not built: reading
history per pass costs 3.3 ms for 38 evaluations, proportional to what is sent anyway.

Net source estimate for the cut: about +240 added (CX-1, -2, -5, -11, -12, -13) and about −2,260 deleted (CX-3 −150,
CX-6 −520, CX-8 −350, CX-9 −330, CX-10 −910), so about −2,000 net, before test deletions.

Storage: SR-H → SR-I; SR-E after SR-D; SR-C any time in group C order; SR-F after CX-10 (prefer deleting the
`turn.clj` readers to converting them). BLOB-K waits on file releases and the fork push.

## Owner questions

**Answered (context cut, 2026-10-08).**

**Q-CX1 (CX-4). The `user=>` prompt in the history.** ANSWERED (owner 2026-10-08): drop the prompt from the history. The transcript prints `<ns>=> ` before each form; the model
copies it, and a copied prompt is read as the symbol `user=>` (one error beside each form, probed 2026-10-08).
1. **(recommended)** Drop the prompt from `seon.repl/text`: the spec's REPL presentation is "the form as sent, then
   its envelope", the envelope's `:ns` shows a namespace change. Three lines deleted, no reader change.
2. Keep the prompt and teach the reader's text-in stage (`seon.sci.forgive`) to drop a line-leading
   `<current-ns>=> `, as clojure.main's own prompt is not code. Adds a text rule to the reader.
3. Both.

**Q-CX2 (CX-12). Which wire format do live runs use by default?** ANSWERED (owner 2026-10-08): "experiment and see what works best": CX-12 builds both, and live runs compare `:transcript` and `:chat` on the same task before a default is chosen. The spec keeps `:transcript` until the competency
table is read; the live run shows format A's cost (the model read its own forms inside a user message and advised
itself).
1. **(recommended)** Land both and make `:chat` the default for live runs now; the competency matrix still scores both.
2. Keep `:transcript` the default until the matrix, `:chat` by agent overlay only.
3. Only `:chat`; delete `:transcript`.

**Q-CX3 (CX-2). Root's model turns.** ANSWERED (owner 2026-10-08): root gets model turns only for an assigned root task (e.g. a merge request); a wake alone never starts one. How root's work becomes assigned tasks: [README-root-tasks](README-root-tasks.md) (RT-1 … RT-14). With "a model turn only with an assignment", root (no assignment) no longer
gets model turns from error notifications or messages. Today merge requests are root tasks worked through the
ordinary REPL by our orchestrator, so nothing is lost.
1. **(recommended)** Accept: a root model turn needs an assigned root task (a merge request is one).
2. Exempt root: root gets a model turn for any wake.

**Answered (owner 2026-10-08 late): option 1.** **Q1 (now CX-13). Where does a pass learn which assignment it belongs to?** The history (S4-03) is "call rows of
the active assignment", but no pass knows its assignment today: the world carries an optional task id only
(`src/seon/turn/world.clj:49-67`), and the assignment is written at the task home, which an agent's branch may not
hold.
1. (simplest, recommended) Read it once per pass when the world is built (`seon.task/assignment` on the task home,
   ~1 ms) and record the 12-character assignment key as a plain value on the call row. Costs one indexed read per pass.
2. Record nothing; derive the boundary at read time from the assignment's transaction time compared with each call
   row's. No write, but it compares times across two branches, which is fragile.
3. Store the key on the agent entity when root assigns, and copy it from there. One more fact that can go stale.

**Answered: option 2, measured first, rebuild only as fallback.** **Q2 (blocks PB-03b). After a reload, should the shared base be advanced in place or rebuilt once?** Today the first
pass after an adoption rebuilds the whole base (measured 428 MB / 180 ms), once per adoption, not per pass.
1. (simplest) Keep the rebuild once per adoption, and only release the previous base at once. Meets "never per pass",
   not "incrementally built up". No new code path.
2. (recommended, matches your shared-base ruling) Advance: fork the previous base and re-install only the reloaded
   namespaces' Vars and program rows. Cost follows the change; it adds a second way to reach a base, which the
   stale-caller tests must cover. Decide after PB-03a's measurement.
3. Rebuild in the background right after adoption, so no agent pass waits on it. Hides the cost without removing it.

**Answered: 1 KB (option 1).** **Q3 (does not block BLOB-K; card uses 1024). Blob compression floor: 1 KB or 256 bytes?** Your approved defaults say
"blob compression floor ~1 KB"; lane blob-konserve wrote 256 B ("below it the LZ4 frame and marker cost about what
compression saves").
1. 1024 B, as approved (card default). 2. 256 B, as the lane measured. 3. 1024 B now, revisit with a measured table.

## Findings recorded while carding (out of scope)
- The blob-konserve work sits uncommitted in the shared tree inside files claimed by `led-storage` (`src/seon/blob.clj`)
  and with an unclaimed untracked schema (`resources/seon/schemas/seon.config.blob.edn`); `deps.edn` (claimed by
  `dials-16gb`) still pins the old konserve sha while the submodule points at `e4334756`. BLOB-K lands it.
- `seon.sci.eval/rendered-evaluation`'s only caller is inside `system-turn`: both deleted (`55ede54a9`, `87326e203`).
- Health at carding time (default, 2026-10-08 evening): `:faulted`; `seon.schema/call-with-projection-state` 78 calls,
  9.4 s mean, 171 s max; `seon.sci.kernel/acquire-arm` refused new evaluations under memory pressure (4 occurrences);
  the cache at 509 of 512 MB.
- A fresh agent's no-change eval request reports the reaching tests already red on main (`bt.slug-test/slug-lowers`,
  `live.text-test/slug-joins-lower-case-words`) as its own reds (probe agent `ctx-cards-probe`): CS-01d's
  "red at the merge base" marking and F11 cover it; a model would read them as its blockers.
- `clojure.test/run-test-var` at an SCI agent's REPL still throws `sci.lang.Namespace cannot be cast to
  clojure.lang.Symbol` (live run, three model passes spent on it): task `seon.sci.eval/run-test-var-throws-on-an-sci-test-var`.
- Exchange text through `seon.repl/text` costs about 0.7 ms per evaluation (27-40 ms for 38 evaluations, 17 K
  characters): 10× what copying stored text should cost; CX-1 landed re-rendering every exchange per call, so this is
  ledger RESPEC R-7.
