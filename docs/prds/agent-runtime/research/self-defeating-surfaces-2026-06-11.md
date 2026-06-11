---
type: research
status: active
tags: [research, agent]
---

# Self-defeating surfaces + agent-blindness audit — 2026-06-11

Read at working tree of `feature/agent-runtime` (post c9af9b9-era, Waves
A+B landed). Method: the [[context-blind-spots-2026-06-11]] approach —
code-level hunt across the four sub-classes (dishonest records, agent
blindness, silent truncation, guessed authority), grounded against a
LIVE post-Wave-B prompt blob (`logs/prompts/BKs-2606111813/
iOy-2606111813.txt`, rendered 18:13 today) and one live REPL probe.
Rows already in [[../open-issues-prd-2026-06-11]] are REFERENCED, not
duplicated.

## TL;DR

Waves A+B fixed the big surfaces (loud eval clips, inventory,
stub tags, the B3 reply guard — all verified present in today's live
blob). What remains is a SECOND RING of the same class, mostly in the
machinery's own failure paths: **success-shaped returns after failed
transacts** (`create!`), **renderer throws swallowed to nil** (the
generic entity-card paths never got the tile's guard), **one throwing
warn-check killing the whole warnings section**, **auto-instrument /
auto-test-run failures that leave stale ✓ marks**, **replay failures
that exist only in a disk log while the rendered ns still claims the
fn is live**, and **a handful of quiet member-block clips** (33 live
schemas already exceed the 200-char shape clip). One authority hole:
agent-forged `:substrate-seed` provenance is warn-only, and the ghost
GC keys off exactly that provenance.

---

## Findings — ranked by easy-win-ness (small fix, big payoff first)

### 1. `create!` returns a success-shaped map after a FAILED transact

- **File:** `src/seon/agent.cljs:636-647` (`create!`)
- **Sub-class:** DISHONEST RECORD
- **What's wrong:** when the agent-entity transact fails, the failure
  goes to `js/console.error` and the fn still returns
  `{:seon.agent/id id}` — the same shape as success. Every caller
  (boot, gym seeding, downstream `start-agent!`) proceeds to install
  triggers and render context for a ghost agent. The code's own
  comment says "everything downstream would chase a ghost" — and then
  lets them chase it.
- **Evidence:**

  ```clojure
  (when (false? (:seon.db/ok? res))
    (js/console.error
      (str "seon.agent/create! transact FAILED for " id ": " …)))
  {:seon.agent/id id}   ;; unconditional
  ```

- **Fix sketch:** return the error envelope (`res`) when
  `:seon.db/ok?` is false; callers branch (errors-as-values, same
  contract as `message!`). Related register row 148 (`create!` drops
  `:seon.agent/turns-cap`) — same fn, could be one unit.
- **Effort:** S

### 2. Generic entity-render paths swallow renderer throws → cards silently vanish

- **File:** `src/seon/render.cljs:451-457` (`render-entity-html`),
  `:556-564` (`render-entity-ai`)
- **Sub-class:** AGENT BLINDNESS (the exact "tile broke invisibly"
  class, on the surface that DIDN'T get the fix)
- **What's wrong:** both fns wrap the resolved renderer call in
  `(catch :default _ nil)`. An agent-authored entity renderer that
  throws makes the entity render as nil — the card disappears from
  the inspector window (degrading to `unknown-entity-card`, which
  blames the KIND, not the broken fn) and from any AI-path consumer.
  The tile path got the two-layer guard + error envelope (asks 9+12);
  these generic paths kept the pre-fix behavior. Worse: the
  inspector's own `"render error: …"` fallback card
  (`src/seon/web/inspector.cljs:221-226`) is DEAD CODE for fn throws,
  because the inner catch in `render-entity-html` eats the exception
  before the inspector's catch can show it.
- **Fix sketch:** same shape as `live-tile/error-response` — return an
  error-carrying response (or at minimum re-throw so inspector's
  existing catch fires); the AI twin says "renderer X threw: msg" so
  the owning agent sees its breakage next render.
- **Effort:** S–M

### 3. ONE throwing warn-check kills the ENTIRE warnings section

- **File:** `src/seon/warn.cljs` `run-checks` (the fn above
  `render-affected-entry`, ~line 875)
- **Sub-class:** degrade-don't-break (ROOT 4) / AGENT BLINDNESS
- **What's wrong:** `run-checks` maps the registry with NO per-check
  guard. A single check fn that throws (e.g. on an unexpected store
  shape — these checks run datalog against agent-shaped data every
  render) propagates up; `ctx/render-section`'s guard catches it and
  replaces the WHOLE `<warnings>` block with one
  `[warnings] render failed: <msg>` line. The agent loses every other
  warning (failed evals, fs denials, bad refs) because one check
  tripped. It is visible-but-total: legible one-liner, but 100% of
  the warning surface gone.
- **Evidence:**

  ```clojure
  (->> checks
       (map (fn [check] (check req)))      ;; no try
       (filterv (comp seq :seon.warn/affected)))
  ```

- **Fix sketch:** per-check try → a synthetic cluster
  `[warn-check-error]` naming the broken check; the other clusters
  render normally. Self-heals when the check stops throwing.
- **Effort:** S

### 4. Auto-instrument + auto-test-run failures are console-only → stale ✓ marks lie

- **File:** `src/seon/eval.cljs:1493-1497` (auto-instrument catch),
  `:1519-1524` (auto-test-run catch)
- **Sub-class:** DISHONEST RECORD + AGENT BLINDNESS
- **What's wrong:** both post-eval automatics are best-effort with
  `js/console.warn` on failure. Two consequences the agent never
  sees:
  1. a fn it just defined with a valid `:malli/schema` is silently
     NOT instrumented — the taught contract ("all specced fns are
     validated, no off mode") is false for that fn;
  2. when the auto-test-runner THROWS (not when tests fail — when the
     run itself dies), the `:seon.test` rows keep their LAST
     pass/fail stamps, and `test-block-ai` renders the old ✓ next to
     the NEW fn source. The context asserts "tests pass" for code the
     tests never ran against.
- **Fix sketch:** smallest honest move: on runner throw, stamp the
  affected `:seon.test` rows (or one derived warning via a check that
  compares `last-passed-at` against the fn row's `created-at`) so a
  stale status renders as `• not re-run since fn change` instead of
  ✓. The instrument failure can ride the existing
  `:seon.fn/schema-error` attr (it already renders in fn blocks).
- **Effort:** M

### 5. Replay failures exist ONLY in the disk log; the rendered ns still claims the fn is live

- **File:** `src/seon/client.cljs:752-760` (`log-replay-failure!`),
  `:810-823` (replay loop — counts + best-effort log);
  `src/seon/log.cljs:24-30` ("Why no DB rows")
- **Sub-class:** AGENT BLINDNESS (post-restart)
- **What's wrong:** when boot replay of an agent-authored fn/schema/ns
  row fails, the failure is appended to `logs/pod-events.log` and
  counted in the boot return value. Nothing reaches the OWNING
  AGENT's next context: the `:seon.fn` / `:seon.ns` store rows that
  failed to replay still render in its `<namespace>` sections as
  normal live code, and the first call dies with an undeclared-var /
  undefined-property error the agent must reverse-engineer. B4+#14
  fixed the host-bundled-require CAUSE (the biggest one), but any
  OTHER replay failure (changed dep, form that no longer compiles,
  store row teed from a since-broken eval) is still invisible. The
  agent has a taught fs path to `pod-events.log` but no reason to
  look.
- **Fix sketch (reactive-context shaped):** don't store "replay
  failed" — DERIVE it. A warn check (or the ns renderer itself) that,
  for the agent's current ns, compares rendered `:seon.fn` member
  rows against the live runtime (`seon.eval/ns-live-on-globalthis?` /
  analyzer entry probes already exist from B4) and tags dead members:
  `[fn my.kb.x/f] ⚠ not live in this process — replay failed at boot;
  re-eval its source (rendered above) to restore it`. Self-heals on
  redefinition; no stored state.
- **Effort:** M

### 6. `seon.agent.search` clips matched line-text with NO marker at all

- **File:** `src/seon/agent/search.cljs:227-230`
- **Sub-class:** SILENT TRUNCATION (the no-marker kind — worse than
  quiet " …")
- **What's wrong:**

  ```clojure
  (if (> (count t) max-line-chars)
    (subs t 0 max-line-chars)   ;; bare subs — no ellipsis, nothing
    t)
  ```

  A grep hit on a long line (one-line fns, register! calls with big
  inline shapes — common in this codebase) returns the prefix as if
  it were the whole line. An agent quoting that line-text quotes a
  fabricated-by-omission line. Contrast: the match-COUNT clip in the
  same ns does it right (`:seon.agent.search/truncated?` flag,
  rendered in the docstring contract).
- **Fix sketch:** append the standard loud marker
  (`" …⟨clipped at N of M chars — read the file at :line-number for
  the full line⟩"`) or a per-row `:seon.agent.search/line-clipped?`
  key.
- **Effort:** S

### 7. Member-block quiet clips in the namespaces section — 33 live schemas already exceed the shape clip

- **File:** `src/seon/ctx.cljs:1001-1005` (the private `clip` — bare
  `" …"`), used at `:1086` (spec, 80 chars), `:1088` (schema-error,
  80), `:1095` (docstring first-line, 280), `:1108` (schema shape,
  200), `:1123` (test source, 240)
- **Sub-class:** SILENT TRUNCATION (quiet-marker stragglers A4 missed)
- **What's wrong:** A4's loud-⚠ rule landed on `cap-result` /
  `truncate-edn` / `cap-edn` (verified: the live blob's system text
  teaches "a clipped display is NOT a clipped value"), but the
  ns-member render blocks kept a bare `" …"`. The 200-char schema
  clip is the sharp one: entity `:map` shapes are exactly where an
  agent reads WHICH ATTRS a kind requires, and the tail (the later
  attr entries) is what gets cut. **Live probe (this audit):** 33
  registered schemas exceed 200 chars pr-str'd —
  `:seon.agent.turn` 722, `:seon.eval` 698, `:seon.test` 649,
  `:seon.fn` 621, `:seon.agent` 530. An agent imitating
  `:seon.agent.turn`'s rendered shape sees under a third of it, with
  a marker indistinguishable from prose.
- **Fix sketch:** route these through the same loud-marker helper
  (`"…⟨clipped N of M — full shape: (seon.schema/schema-definition
  :kw)⟩"` — the drill teaching per clip site). The spec/doc clips can
  keep small limits; they just need the loud form.
- **Effort:** S–M

### 8. Turn stuck `:running` forever when the error-close tx fails (swallowed)

- **File:** `src/seon/agent.cljs:918-925` (`with-turn-body!` catch:
  `(catch :default _ nil)` around the :error-close transact);
  `:1120-1126` (`run-turn!` outer catch, same pattern for the
  state-heal tx)
- **Sub-class:** DISHONEST RECORD
- **What's wrong:** if the body throws AND the error-close transact
  also fails, the failure is swallowed: the turn row stays
  `:seon.agent.turn/status :running` forever and (in the
  `run-turn!`-also-fails case) `:seon.agent/state` stays `:running` —
  and the wake trigger's `(not= :running state)` guard then refuses
  every future wake. A double-fault bricks the agent with zero
  surface anywhere (not even console). Same family as register row
  157 (`record-eval!` double-failure) — the dishonest-record class's
  "the recorder's own failure is the silent one" signature.
- **Honesty note:** likelihood is low (requires two consecutive
  transact failures), but the blast radius is "agent permanently
  dead, looks merely idle/busy".
- **Fix sketch:** console.error at minimum (parity with
  record-eval!'s ladder); better, the stuck-:running case is
  DERIVABLE — a warn check for turns `:running` older than N minutes
  with no in-flight latch, which also catches process-crash-mid-turn
  (a real occurrence the `resume-marker` handles only for evals).
  Pairs with register #23 (`:seon.agent.turn/error` attr).
- **Effort:** S (loud log) / M (derived check)

### 9. Forgeable `:substrate-seed` provenance — and the ghost GC trusts it

- **File:** `src/seon/db/internal.cljs:905-919`
  (`warn-on-seed-origin-forge!` — "WARN-ONLY guard … Returns
  merged-opts unchanged (see the enforcement TODO above)")
- **Sub-class:** GUESSED/FORGEABLE AUTHORITY
- **What's wrong:** an agent-scoped tx claiming
  `:seon.db/origin :substrate-seed` gets a console.warn and a counter
  bump, then COMMITS with the forged provenance. This was tolerable
  when origin was only descriptive — but
  `seon.client/prune-substrate-ghosts!` (landed today, register row
  167) now makes provenance LOAD-BEARING: substrate-claimed ns/fn/
  test/schema rows absent from the fresh boot index are RETRACTED at
  boot. An agent row that (accidentally or via a copied tx-meta map)
  carries `:substrate-seed` origin and isn't in the boot index is GC
  bait — the agent's own code deleted at next restart, silently,
  by a mechanism whose correctness assumes unforgeable provenance.
  Replay selection's agent-row protection rests on the same datom.
- **Honesty note:** I have NOT reproduced a forged-row GC live; the
  chain is from code reading (forge guard returns unchanged +
  GC keys on origin). Labeled: high-confidence mechanism, unverified
  end-to-end.
- **Fix sketch:** the enforcement TODO: inside an agent scope,
  REWRITE the origin to `:agent` (or reject the tx with an envelope).
  One mechanism, kills the class.
- **Effort:** S

### 10. Hot-reload trigger re-arm failure leaves agents unwakeable, console-only

- **File:** `src/seon/client.cljs:1562-1567` (the `.catch` on the
  reload re-arm promise chain)
- **Sub-class:** AGENT BLINDNESS (and human blindness)
- **What's wrong:** after a pod hot-reload, `ensure-bootstrap!` →
  re-arm of every agent's user-message trigger. If that chain
  rejects, the catch logs `"re-arm FAILED"` to console and the pod
  keeps running, healthy-looking: HTTP serves, tiles render, messages
  transact into the store — and NO agent ever wakes for them. The
  human sees their message in the chat and an agent that never
  answers. Boot has the opposite policy ("FAIL LOUD … exiting (no
  local fallback)", client.cljs:2012-2016); reload kept the soft
  path.
- **Fix sketch:** at minimum surface it where the human looks — the
  inspector dash already renders per-agent state; a derived
  "trigger armed?" probe (listener registry has the key or not) on
  the agent card + a warn check would make the failure visible and
  self-healing. Or match boot: exit on re-arm failure.
- **Effort:** S–M

### 11. `stash-result-raw!` failure makes `lookup-result` tell a same-session lie

- **File:** `src/seon/eval.cljs:592-602` (stash: console.warn +
  ignore) with `:639-645` (the "prior session" branch)
- **Sub-class:** DISHONEST RECORD (low likelihood — UNCERTAIN)
- **What's wrong:** if the globalThis stash write fails, the eval row
  still records ok=true; a later `(result <id>)` finds the row but no
  stash key and returns the "is from a prior session — did not
  survive the process restart" envelope, in the SAME session. The
  diagnosis the agent gets is confidently wrong (it will distrust the
  resume marker, not the stash). `js/Reflect.set` on globalThis
  essentially never fails, so this is a completeness note, not a
  fire. Counterpoint worth recording: the B3 guard FAIL-SAFES here —
  an unstashed ok-eval's lookup miss returns an `ok? false`-shaped
  envelope, which `envelope-failure?` counts as a failure, so a reply
  after a stash loss is refused rather than blessed. Correct
  direction.
- **Fix sketch:** fold the stash-write result into the eval row
  (e.g. omit/flag the result-var id on the value line) so the
  transcript never advertises a `(result <id>)` that can't resolve.
- **Effort:** S (when touching eval.cljs anyway)

### 12. Persisted `:seon.schema/source` boot-index clip is quiet and EDN-breaking

- **File:** `src/seon/client.cljs:1220-1251` (`schema-source-cap`
  1000; clip appends bare `" …"`)
- **Sub-class:** SILENT TRUNCATION (pathological-only — UNCERTAIN it
  ever fires today)
- **What's wrong:** the boot indexer persists each registered schema's
  pr-str'd form as `:seon.schema/source`, capped at 1000 chars with a
  bare `" …"`. The docstring promises "the full shape of every attr
  is one entity-read away"; over the cap, the entity-read returns an
  unbalanced, silently-incomplete EDN string. My live probe's largest
  shape today is 722 chars, so nothing currently crosses it — but a
  downstream's big entity :map will, and the failure mode (agent
  read-string's it → reader error, or quotes it as complete) is the
  docstring-fiction class. Replay is NOT at risk (only
  `registration-call-source?` rows replay; boot-index rows don't).
- **Fix sketch:** loud marker + the drill
  (`(seon.schema/schema-definition :kw)`), same as finding 7.
- **Effort:** S

### 13. The creation-turn tutorial models the raw 1.5KB tx-report as the normal transact result

- **File:** rendered transcript of every fresh agent (live blob
  `logs/prompts/BKs-2606111813/iOy-2606111813.txt`, the
  `seon.db/transact!` tutorial eval — the result line is the full
  `#datahike.db.TxReport{…}` at ~1.5k chars)
- **Sub-class:** CONTEXT THAT DEFEATS CONSULTATION — UNCERTAIN
  (design tension, not a defect per se)
- **What's wrong:** `message!` was redesigned (#26 A3) on the explicit
  rationale that "the raw transact tx-report … ~1.5k transcript chars
  per reply taught nothing". The substrate's own creation turn now
  SHOWS that exact tx-report as what a successful transact looks
  like — the loudest result in the agent's first prompt. It is
  honest (transact! really returns it) and REPL-realism argues for
  showing truth; but it ambient-teaches "echo big result maps"
  (the s12 47-evals-of-self-echo fuel) and buries the one bit that
  matters (`:seon.db/ok? true`). The s32 question ("does message!
  return the full transact report or something smaller") is answered
  ambiguously by this surface.
- **Fix sketch:** if anything: have the tutorial bind the result and
  show the ok?-projection move
  (`(:seon.db/ok? (seon.db/transact! …))` or the result-var drill) —
  teaching the ECONOMY pattern on the noisiest value type. Flag for
  the executable-teachings harness owner to decide; do not change
  transact!'s return.
- **Effort:** S (content), but gym-gated like all teaching changes

### 14. `parse-spec` failure renders a specced fn as `:unspecced`

- **File:** `src/seon/warn.cljs:113-119` (`parse-spec` → nil on
  unreadable), feeding the corpus checks; same attribution appears in
  ctx fn flags when `:seon.fn/spec` is absent
- **Sub-class:** DISHONEST RECORD (minor)
- **What's wrong:** a `:seon.fn/spec` string that doesn't read back
  (e.g. contains `#object[…]` fn refs) is treated as no-spec: the
  warn checks may flag the fn `no-malli-schema`/`no-input-spec` and
  tell its owner to add a schema IT ALREADY HAS. The agent's
  "fix" (re-adding) is a no-op loop. Mostly mitigated upstream
  (`var->fn-row` already omits non-pure-data specs WITH a loud log,
  client.cljs:1758-1773) — this is the read-side twin lacking the
  same honesty.
- **Fix sketch:** distinguish "no spec" from "spec present but not
  pure-data" in the affected line ("spec unreadable — make the
  :malli/schema pure data"), reusing var->fn-row's wording.
- **Effort:** S

---

## Checked and found CLEAN (don't re-audit)

- **`lookup-result` miss semantics** (eval.cljs:604-645) — honest
  three-branch envelope (typo / errored / prior-session); errors are
  values; never throws.
- **`message!`/`reply!` + the B3 guard** (agent/message.cljs) —
  refusal names each failed form; unverifiable counts as failure
  (the stash-miss envelope is ok?-false-shaped → flagged); force is
  explicit; blank-content and no-from are legible envelopes. The
  internal 60/160-char clips inside refusal lines are quiet but the
  envelope points at the transcript for the full text — acceptable.
- **Tile pipeline** (render.cljs `render-agent-tile` + ctx
  `live-tile-section`) — fn-call AND serialization guarded (asks
  9+12 landed); a broken tile renders an error envelope to BOTH the
  human (banner) and the agent (ai twin). The note "vanish is
  indistinguishable from unwired, banned" is now true for tiles —
  finding 2 is the same rule not yet applied to generic entity cards.
- **`ctx/render-section` per-section guard** (ctx.cljs:1746-1771) —
  every section throw degrades to a legible one-line
  `[name] render failed: …` that self-heals; the missing-fn case
  even teaches the fix. (Finding 3 is about ONE section — warnings —
  aggregating many independent checks behind that single guard.)
- **Transcript eviction** (ctx.cljs:1424+, 1508-1528) — messages
  exempt from eviction (P22 fix pinned), eval items drop oldest-first
  with an explicit `;; … N older eval items elided` note.
- **A4 loud clips** — `cap-result` / `truncate-edn` (ctx.cljs:293-375)
  and `cap-edn` / `result-row-cap` preview (eval.cljs:973-1090) all
  carry ⚠/sizes/drill teaching; live blob confirms the system text
  teaches "a clipped display is NOT a clipped value".
- **Stub `<namespace>` tags** — self-describe in today's live blob
  ("⚠ stub — source not indexed here; do NOT guess its contents…").
- **`store-inventory`** — attr-namespace keyed with per-attr counts,
  deterministic order, verified in today's live blob.
- **`seon.agent.fs`** — every op returns `->err` envelopes; denials
  are values; SEON_FS_ROOT/LOCK are declared env data (not inferred
  authority); fs-denied evals get their own warn check.
- **LLM failure path** (agent.cljs `ask-and-eval!` + `call-llm!`) —
  provider error → visible ⚠ self-message + turn :error; the single
  transport retry is recorded (`:seon.agent.turn/llm-retries`) whether
  it healed or not. Honest.
- **Hop-cap refusal** — wake refusal is console.error PLUS the
  `check-hop-exhausted` runtime warn check (global scope — the sender
  sees it too, cross-agent visibility by design).
- **Boot fail-loud** (client.cljs `-main` auto-boot `.catch` →
  `process.exit 1`) — no half-up pod. (Finding 10 is the RELOAD path
  that didn't inherit this.)
- **`persist-prompt!` soft-fail** — deliberate, documented (blob tier;
  chars projection survives); debugging-only loss.
- **Eval timeouts** — race-timeout returns a legible error envelope
  naming the ms + no-preemption caveat; budget override consumed even
  on non-promise values (no leak into the next form).
- **`agent_view.cljs:64`** own-eid catch→nil — correct fallback for
  the agent-entity-not-yet-created case, commented with the live
  incident.
- **Inspector SSE write paths** — per-connection best-effort with
  logging; one dead socket can't kill the push loop or the page.
- **`seon.warn/fs-denial-text`** — parse failure falls back to a
  visible clip of the raw edn, never empty.

## Already in the register — referenced, not re-filed

- `record-eval!` double-failure silent loss → row 157 (finding 8 here
  is the same signature in `with-turn-body!`; cite both in the unit).
- `d/pull` guard bypasses in `handlers/fn.cljs:60` +
  `handlers/message.cljs:41` (bare try masking typos) → row 181.
- DIS transient false-empty datalog read (DISHONEST READS) → row 182.
- `unmarked-entity-kinds` standing self-warning → blind-spot #11 /
  fix-everything Wave C item 6. **Live confirmation for that unit:**
  still firing verbatim in today's 18:13 blob
  (`iOy-2606111813.txt:2094-2101`, "Affecting: :seon.handler/key …
  Please correct before moving on").
- ctx `evals`/`current-ns` reading live `db/*conn*` instead of the
  composer snapshot → complexity-audit register row (read-leg
  unification).
- MCP bridge stale-runtime pinning → agent-reported issues row 149.
- fs read paging / `SEON_FS_LOCK` → Wave C items 3-4 (fs.cljs's lock
  is now implemented in source — the Wave C row can be re-verified).

## Suggested unit groupings (Wave C+ feed)

1. **Honest failure envelopes micro-unit** (findings 1, 8-loud-log,
   11): agent.cljs + eval.cljs touchpoints, all S.
2. **Entity-render guard parity** (finding 2): render.cljs only,
   mirrors the landed tile guard.
3. **Warn-section resilience + honesty** (findings 3, 14): warn.cljs.
4. **Loud-clip stragglers sweep** (findings 6, 7, 12): the A4 rule
   applied to search line-text, ns member blocks, boot-index schema
   source.
5. **Post-restart truth** (findings 4, 5): stale-✓ + replay-failure
   visibility — both are "derive liveness, don't trust stamps";
   one mechanism candidate (live-probe member rows at render).
6. **Provenance enforcement** (finding 9): the existing TODO in
   db/internal.cljs, now load-bearing because of the ghost GC.
