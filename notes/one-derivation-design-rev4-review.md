# Review: one derivation, revision 4 (independent, read-only)

Checked against jolt/view-parts at f9f3dd39a.

**Verdict: approve with changes.** §2 to §4 are sound in direction. §1 claims more than its mechanism gives, §5 contradicts its own rule and stores a derived value, and §7a should go to neither of the options it offers.

## 1. §1: is the request a pure function of the db at the turn points?
- **Mostly already true today.** Each turn's request is rebuilt from the db: `context/render` at turn.clj:270. Each turn records a basis (turn.clj:198-208) and ref-only `:seon.turn/sent` (facts.edn:461). The test results are in the basis's `:observed`. Call ids, reasoning and reply are in the db (turn.clj:331-342; repl.clj:405). The help and system pages are db sections (context.clj:18-35). The new part is "code as of t".
- **"Code as of t" does not give byte stability.**
  - Forms keep `:form/src` with history: superseded forms are only retracted (program.clj:518), and `keep-history? true` (default.edn:117).
  - But loading one render's old form into a scratch namespace runs it against today's printer, `select`, helpers, aliases and config.
  - Config is a file, not the db (config.clj:82). Renders read it at render time (context.clj:155, 180, 221, 636; repl.clj:401).
  - The proof as written ("a render redefined between them") would pass while a merge to `seon.print` breaks the prefix.
  - It is also a second loader beside `program`'s, and it runs old, possibly agent-written, code. If it runs in root, that undoes rev3's rule that an agent draws its own parts.
- **The prefix is not stable at every turn.** `kept`'s cut deliberately jumps (rev3: turns 13 and 28), so "n-1 is a byte prefix of n" fails at those turns by design. State that.
- **The turn point is a commit uuid, not a t** (facts.edn:438). The design needs one or the other, named.
- **The cost is understated.** Re-deriving each eval's ripple needs the view at t-1 and at t, not "the session once". The per-part memo bounds it to the parts that changed.

## 2. §2: the eval's keys
- **Line numbers have drifted:** `:cites` is at repl.clj:389 (not 381/403), `:line` at 401 (not 396), `printed-run` at context.clj:456, `kept` at 527.
- **Missing from the table:**
  - `wrap-instrument`'s `:contract` and `:warnings` (repl.clj:272-307). `response` (331-356) drops both, so the uncontracted-definition warning never reaches the agent today. That is a live defect; file it as a task.
  - `wrap-caught`'s error fold to root (146-160).
  - `keep-source!` (47).
  - The ns-metadata fix (219-221).
  - The `result/eN` intern (461).
  - `with-branch-schemas` (256).
  These stay as evaluation; the design should say so, not "nothing else runs".
- **Stored text contradicts "nothing sent is stored as text".** A non-data value is stored as `:printed` text (repl.clj:393-398). Name it as the one exception: only text exists for such a value.
- **Moving `:redefined` loses nothing for the model.** Every eval's transaction carries `:seon.tx/eval` (agent.clj:161; ctx.clj:139), and the tool answer is drawn at the next request anyway.
- **Two conditions on that move:**
  - `meaning-changes` reads the db *before* the transaction (repl.clj:232). The render must read as of t-1 and over all of the eval's transactions, one per defining form.
  - Outside callers (`seon_eval`) read `:text` at once. Their server must draw the eval part, or they lose `:redefined`.
- **`:cites` must be parsed as of the eval's start (t-1).** record-details' own docstring says so (repl.clj:381-386): "a name the eval itself defines is not yet one".

## 3. §3: the tool answer's ripple
- **It is available in time.** Each eval commits before the next one runs (`evaluated!`, turn.clj:210-218), and the request is assembled after all of the turn's evals.
- **There is no circularity, if three rules hold:**
  - the ripple leaves out the eval's own conversation part (the example's `ctx: [:evals "e14"] added` is noise);
  - it is computed at that eval's t, not at the turn's end;
  - a part change goes to the tool answer when the agent's own eval caused it (by `:seon.tx/eval`), else to the world message's `[page]`, never to both.
- **Send the re-rendered parts by key only at first.** Their new text duplicates `[page]`.
- **Reuse `meaning-changes` as the render's function.** Do not write a second one.

## 4. §5: inbox entries
- **The "one rule" contradicts itself.** The turn's own test run is a change the agent did not make in an eval, yet the design says it is not an entry. Either the world message is "the ctx diff minus the agent's own eval transactions, by provenance", with entries only for words, or the test run writes an entry.
- **`:seon.message/diff` stores a derivation.** That is the same second copy §7a argues against. It also makes root (reconcile) a second producer of the agent's ctx paths. An entry should carry its fact (kind and ref). The agent renders the diff from its own ctx.
- **Wake logic:**
  - `branch-messages` (reconcile.clj:114-119) and `taken-messages` (714-723) count every `:seon.message/to` a run. Without a kind filter in both, verdict and main entries wake parked sessions.
  - `:seon.message/body` is a required string (facts.edn:396-400).
  - The stage boundary is not a main fact today. It is a `:briefed` request (reconcile.clj:832-836), taken as a non-fact message (turn.clj:175-196).
- **Build only `:message` and `:stage` now.** `:verdict` and `:main` already reach ctx: the verdict through the pull, merges through `:seon.tx/merged`.

## 5. §7a: facts or renders
Neither. Related and similar read vectors that are in the db (context.clj:143-221; `related!` is keyed by `vectors-t`, turn.clj:83-99), so both are pure functions of the db at t.
- **Make them derived members of `(ctx db e)`**, keyed by their inputs, so `(html part)` and `(ai part)` stay pure in `[f v]` (rev3 §3) and the identity memo holds.
- **A stored fact is a second copy.** A render that reads the db breaks render purity and the memo.
- **Cited references** are the same: a pure db read as of the eval's start.

## 6. Second mechanisms, guards, clocks, overbuilding
- **Second mechanisms:** the scratch-namespace loader, and the stored diff. No new guard or clock.
- **Overbuilt for the slices:**
  - as-of code loading (provider caches expire across a resume anyway);
  - four message kinds;
  - re-rendered text in the tool answer.

## Top changes, ranked
1. **Drop as-of code loading.**
   - `:seon.turn/sent` keeps each term's key, mode and t, plus a digest of its bytes.
   - The live process keeps terms in a content cache, as `:printed` does today.
   - The debug view and a resume re-derive the terms with current code and show any digest mismatch as drift.
   - Restate the invariant as "byte prefix within a session, except at the cut's jumps". Make config an explicit term input.
2. **§5:**
   - one rule with no exception;
   - entries carry facts, not stored diffs;
   - a kind filter in `branch-messages` and `taken-messages`;
   - only `:message` and `:stage` for now.
3. **§7a:** lookups are derived members of ctx, as of t; delete `:seon.run/story` and `:seon.task/related`.
4. **§2:**
   - complete the table;
   - read `:redefined` and `:cites` as of t-1;
   - make the server draw the eval part for outside callers;
   - file the dropped `:warnings` and `:contract` as a task.
5. **§3:** ripple by provenance with one place per change, keys only, the own part left out, at the eval's t.
6. **Fix the cost line:** a ripple's re-derivation costs a view diff per eval, bounded by the memo.
