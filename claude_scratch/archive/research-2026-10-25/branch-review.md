# Review: jolt/agent-conversation (git diff main...HEAD), read-only, nothing run

Verdict: MERGE AFTER LISTED FIXES (F1-F4). The rest is follow-up. Read-only review: I could not run
tests; claims about races are from reading code, each with its scenario. The Datahike pin 6dc9ce64
is pushed to seantempesta/datahike (verified with gh api); main's pin is 698a2faf, so this is an
intended bump.

## Blocks merge / fix before merge

F1. Stale components behind a newer main: src/seon/render/proc.clj:691-698 (:screen-main), :114 (main-info), :746 (:main).
  Defect: the :screen-main answer advances :main-db (main-info) and marks the VIEW dirty, but invalidates
  no cached component. Only the writer's :main input invalidates (comps-touched on that commit's datoms).
  Scenario: commits t3,t4 are queued in the bounded :main buffer (or the proc is busy in a pass); an agent's
  screen request is answered from main as of t4; the cached [:main eid as] components were rendered at t2,
  so screen-of returns {:t t4 :page <t2 content>}. The :main messages fix the cache later, but the answer
  already went out. The code's own comment says "the commits this proc takes can lag main's".
  Fix: one mechanism for both dbs. When main-info advances :main-db, invalidate comps-touched on the
  datoms-diff of old and new main (as the branch path does at :705-712), O(changed); the :main input then
  needs only main-info.
F2. Components are keyed by branch NAME, rendered from a per-view db at a per-view t: proc.clj:387-409 (db-key, comp-entry).
  Views [:a3], [:debug :a3] (and a screen) each read the same branch separately (:branches view), at
  different t. A view whose own branch read lags can fill the shared cache after another view invalidated it.
  Scenario: the agent commits e7. View A's answer arrives, invalidates the touched components and (touched st
  moved) marks B dirty. B, still on the older db, walks first (or its :more or :toggle fires) and re-fills
  [branch eid as] from the older db. A then renders from the cache: stale, and nothing touches it again.
  The sort order of view keys (":a3" before "[:debug :a3]") hides this for those two views only.
  Fix: key components by [branch t-of-the-db-read eid as], or hold ONE branch db per branch (not per view).
F3. The screen can be answered from a branch read that predates the agent's own commit. proc.clj:~600 (screen answer), :ask-branches.
  The agent commits (per eval, new) then sends :screen; the commit EVENT and the screen request travel
  different paths (events mult vs api->root->render). ask-branches re-reads only when :commits/:t changed
  there. If the screen request wins, the screen shows the agent's page without its latest evals/task state,
  stamped with main's t. Nothing in the request carries the t the agent needs.
  Fix: the agent's :screen request carries its (:max-tx db) (or commit id); the proc answers only when its
  branch read covers it.
F4. Per-commit and per-screen work is O(the agent's evals), against design section 8 (O(change)) and the 2026-10-25 ruling.
  test/seon/render_cost_test.clj:182 (a-page-walks-each-db-once) blesses :per-n 49,500 bytes per eval of
  history for ONE walk (49 MB at 1,000 evals). Every eval now commits (turn.clj:171), the new
  [e :seon.eval/agent a] datom touches the agent view's reverse read, and the view walks all rows again.
  Every screen request does a full walk too, because drop-view throws the held page away each time.
  Over a run that is quadratic in evals. The branch itself files page-walks-only-the-rows-shown as owed.
  The :main path is also unbatched (:746): each commit does main-info (tasks, problems/health), a full pass
  and system-value + render/ai, and the bounded buffer parks the WRITER when this proc lags. Drain queued :main
  messages as :events does, apply the union of datoms, run one pass.
  Fix: either land the rows-shown walk now, or the owner states the O(evals) walk is accepted and names the
  step that removes it. The :main coalescing is small and should go in.
F5. Cache leak in headless roots: proc.clj:599 (screen-only views dropped), :445 (forget).
  drop-view releases a view's components but only the :views message calls forget. With no tab (bin/prove, e2e,
  agents' screens) every agent's rendered components (all shown rows and tiles) stay in :components and
  :comp-index forever. Fix: call forget after the drop-view reduce.

## Fix before merge (smaller)

F6. print-once is untested where it is used. turn.clj:203 threads :printed; no test passes :printed to
  context/render or compares a threaded request against a cache-less one (context_test's prefix test calls
  render with no :printed and has no evals). Claimed properties, "same bytes every turn", "debug read-back ==
  sent", "a restarted agent regenerates the same bytes", are only measured by hand (commit message).
  Add one test: a multi-turn run with repeated values; the threaded and cache-less messages are equal
  turn by turn, and sent-messages(db, turn) equals the messages sent.
F7. Folds point at evidence that history may have removed. context.clj:288-309 (fold-of/printed-run) vs :347 (kept).
  A later response prints `(:value result/e12)`, bytes fixed at first print. kept may later collapse e12 to a
  one-line or drop it, so the model sees a form that reads a value it can no longer see (and after a
  restart result/e12 is unbound: only the live process interns it; the "reads the record" fallback in
  docs/render.md:87 is not implemented, repl.clj:~410). Decide: fold only against records the history
  budget will keep whole, or say so in the docs and add the record-reading fallback with the park/resume work.
F8. Dead code in src (nothing reads it):
  context.clj:536 request-text (also emits ";; ---- " text), context.clj sections (no caller),
  context.clj turn-text and sent-messages (used only by e2e/tests), vector.clj:105 rrf (test-only; its
  docstring says "nothing calls it"), and its config key :seon.config.vector/rrf-k. The 2026-10-24 ruling
  keeps the embedding machinery for exploration, not new unused functions. Delete, or add a caller.
F9. Special-casing the rulings forbid ("no special treatment anywhere"): proc.clj page-value (= :root view)
  branches and root-only nodes; location (:root); ui.clj comp-id/component `as` modes (:side :chat :debug),
  (= :user id), "screen" by-string filter (ui/chat), :seon/off-screen attribute and chat-parts/wrapper-tags
  in screen-hiccup (proc.clj ~:236). The design says root's page is the same view with root as the entity.
  Not a defect by itself; needs the owner to accept it or a task naming how it goes.

## Follow-up

- kept (context.clj:347-362): step-up clamps to n-1, so while n <= history-step and the history is over budget
  the boundary moves each turn (prefix cache breaks). Small.
- printed-run keys :parts by the bare id string, shared by evals ("e5") and messages ("m-..."); key by ref.
- record-details (repl.clj:358) stores any plain value whole, on main too for root's outside evals
  (server.clj eval): O(value) per eval, plus an O(value) plain? walk, no bound. Storage dedupes by content
  key but main grows with every large value. State the intended bound or measure it.
- The debug read-back re-prints with the config in force NOW (budgets via cfg/value), not as of the turn. Equal
  to what was sent only while config is unchanged.
- records (context.clj:367) scans every eval of the agent per turn (a >= filter after the index scan) and
  request does O(history) work in kept/sent. Cheap, but the cost proof (context_cost_test) covers
  printed-run only, not request. Add request to the proof, or say what is not covered.
- a-blocked-agent-ends-on-its-socket-eof (agent_test.clj:157) went red once and was re-run (commit fa9f597d5).
  AGENTS section 3: a flaky result is a defect to fix at its cause.
- seon.agent/await-reply! (agent.clj) pushes other-k :reply messages into :pending; they are later
  handled as unknown ops. Low.
- warm! (root.clj:640) does not exercise with-reads, comp-entry or the new request path; the first render
  and first turn are cold in forked processes.

## What I checked and found sound
- Reads recording: tests cover pull, point query, attribute scan, schema datom (touches everything),
  retraction, go blocks inside a flow step; matching cost proofs are linear in datoms, flat in reads.
- Branch invalidation (proc.clj:700-712): datoms-diff of held vs new db, O(changed); nil held invalidates
  everything from that branch.
- Failed call: :seon.turn/sent is committed before the call (turn.clj ~:205) and the error lands on the same
  turn entity by upsert; a-turn-records-what-it-sent asserts the as-of reads.
- Eval record: {:printed :type} fallback for non-plain values; plain? refuses lazy seqs, records, sorted
  collections; test a-record-keeps-its-value covers atoms, lazy seqs and vars.
- No new schema attributes or config keys beyond the rulings (the other adds are renames of
  :seon.cluster.eval/*, which the 2026-10-24 design approval covers, and :seon.config.ai/reasoning-pair as the
  provider schema check the ruling asked for). No ";;" reaches the model (docs/agent.md and model.clj clean;
  the ";; ----" in request-text is dead). No old section 7a words in added text. Views are hiccup; :ai is
  used only by eval/message conversation messages (the :ai renders in render/ai.clj remain for root's context,
  pre-existing).
- One mechanism for the screen path: agent -> api -> render :page with :screen, answered on the caller's
  route; replies reuse the :reply route that :prove-reply used.
