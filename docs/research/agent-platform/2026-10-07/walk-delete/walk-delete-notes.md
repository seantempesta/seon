---
type: notes
status: active
created: 2026-10-07
tags: [notes, agent-loop, deletion]
---

# walk-delete: pull out the old multi-hop context (lane walk-delete, 2026-10-07)

Owner, 2026-10-07: "Pull out the old multi hop stuff." Root correction, same day: "don't invent new systems, improve
the existing ones". The turn loop stays the agent loop. Lane loop-pass makes the seam edit in `src/seon/turn.clj`, so
the model pass sends the proc transcript (S4) instead of `seon.cluster.prompt/prompt`. walk-delete deletes
what is left once that seam lands. Until root says loop-pass has released `turn.clj`, this lane edits nothing in
`turn.clj`, and nothing whose only live caller is in `turn.clj`.

## Probe

- `bin/seon status`: default pid 28709, `:faulted`, 16 open faults. Top faults:
  - `seon.turn/show-row` → `seon.sci.eval/shown-failure` nil error (task 7f5dfb6ad7a2);
  - `seon.sci.arm/write-event` nil `:line` (task db92135a9301).

  Neither is in this lane's files.
- `my.program/callers` takes a map (`{:seon.program/subject sym}`); calling it with a bare symbol is refused. Its
  refusal example prints `:seon.db/db #object[datahike.db.DB ...]`, a live object in an error example. This is
  ugly but cheap, so not filed separately.
- Census method:
  - clj-kondo analysis of `src test` (9 s);
  - a babashka closure script that keeps every src Var whose src callers are all dead
    (`scratchpad/walk-delete/closure.clj`);
  - `rg` for quoted-symbol and `requiring-resolve` references, which analysis does not see.

## Census

DELETE = model-context only. KEEP = shared with eval_clj/MCP, tests, web pages or boot. AFTER-SEAM = model-context
only, but its last live caller is in `turn.clj` (`call-turn`, `open-turn`, `generate-turn`), so it goes in slice B.

| Var / thing | Live callers outside the old context | Class |
|---|---|---|
| `seon.render/walk`, `call-with-walk-context`, `*walk-context*`, `walk-error`, `ambient-database-value`, `repl-state`, `selected-walk-units`, `render-walk-neighborhood` delay | none in src; nothing binds `*walk-context*` in production, so the agent-facing `(seon.render/walk)` answered "No calling agent is bound" | DELETE (slice A) |
| getting-started instruction text: "Everything above is the output of `(seon.render/walk)`" | instruction seed; now false | CONVERT (slice A) |
| `seon.cluster.prompt` (whole ns: `prompt`, `select`, `compose`, calibration, `capture-mismatch`) | `turn/call-turn`, plus transcript debug views (`render-outline`, calibration in ledger) | AFTER-SEAM |
| `seon.context` (selection, append, compact, capture, contribution hash, `message-custody`) | `turn/call-turn` (capture), `turn/compact!`; `message-custody` is called by `transcript/message-entry` | AFTER-SEAM; `message-custody` is converted or deleted with the transcript entry |
| `seon.render/acquire-context!`, `seon.render.web/derive-context!`, `change-context`, `history-segments`, `turn-function-result`, `render/retained-program-current?` | prompt; debug session, outline and ledger views; the page's context actions | AFTER-SEAM; the ledger and session views are converted to read evaluations directly |
| `seon.render.walk/history` | `derive-context!` only | AFTER-SEAM |
| `seon.render.walk/neighborhood` | `seon.render.web/page-result` (namespace pages) | KEEP; `turn/declared-sources` drops its call |
| `seon.render.walk/{root-acquisition, root-pull-plan, graph, graph-structure, related, with-related, continue}` | web pages and value renders | KEEP |
| `seon.render/shared-cache` | `derive-page!`, `render-pass`, `retained-interest`, `graph-debug-html`, `invalidate-runtime-derived-state`, walk `acquired-tree` | KEEP: it is the web page cache. Only its `derive-context!` use goes. It is an atom cache, not a `seon.cache` kind; see "What remains" |
| `seon.turn/system-turn`, `declared-sources`, `latest-evaluations`, `system-plan`, `generated-read-fault`, `read-only-evaluation?`, `code-currency`, `code-current?`, `unit-read`, `source-events`, `source-key`, `compact!`, `compact-call`, `append-generated-call/tx`, `cluster-agent-acquire-context!` | `open-turn` refresh, `generate-turn`, web context actions | AFTER-SEAM (turn.clj, held by loop-pass) |
| `seon.turn/record-evaluated-call` | `seon.mcp` records MCP runs through it (`:seon.turn.batch/record-call`) | KEEP |
| `seon.turn/record-evaluated-tx` | `system-turn` only | AFTER-SEAM |
| `seon.turn/generated-run-tx` | `seon.bootstrap/seed-tx` (loop-pass owns bootstrap) | KEEP until bootstrap stops seeding `:generate` runs; `generate-turn` must still close such a run |
| stored `:seon.cluster.eval/read-evidence` (writer `turn/receipt-read-evidence-tx`) | readers: system-turn refresh; `eval/of-agent` default selector; transcript reread summary; web preview evidence | AFTER-SEAM: the writer and the schema attribute go with the refresh; transcript `reread-summary` goes with it. RESET NEEDED for stored rows |
| `seon.eval/of-agent` | agents (SCI), tests (21), loop-pass's history | KEEP (default selector drops read evidence after the cut) |
| `seon.print/value-at`, `seon.print/render-elision-ai` | dig forms and the elision pair in `seon.print.edn` | KEEP |
| `seon.db/diff`, `seon.db/apply-diff`, `seon.sci.kernel/pending?`, `seon.render/render-form-value`, `seon.sci.eval/rendered-evaluation`, `seon.ai.tokens/budget-report` | system turn or prompt only | AFTER-SEAM. `seon.sci.eval` is held by repl-lookup (patch to root); the others are unclaimed |
| web debug page `system-turn` and `compact` actions; `:seon.render/context-action` enum | `change-context` | AFTER-SEAM (the golden `next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake` posts `action=system-turn`; deleting a golden needs root) |
| web debug page `virtual-turn` action | `turn/virtual-turn!` (a fixture reply through the ordinary pass) | KEEP; route it to `change-context` directly, not through `acquire-context!` |

Unsure (STOP, listed for root):

- The debug page's session, ledger and outline views are built on `acquire-context!` (saved shown text folded as
  the prompt). Their non-context content (turn list, evaluations, effects, problems) must keep working. Converting
  them to read `eval/of-agent` directly is the plan for slice B; the outline ("the prompt as sent") and
  `render-ledger-context` are context views and go.

## Slice A (outside turn.clj, landed now)

The agent-facing `seon.render/walk` tool and its ambient custody:

- delete `seon.render/walk`, `call-with-walk-context`, `*walk-context*`, `walk-error`, `ambient-database-value`,
  `repl-state`, `selected-walk-units` and the `render-walk-neighborhood` delay;
- convert the getting-started instruction text that claimed the context "is the output of `(seon.render/walk)`";
- delete the SCI leg of `seon.sci.eval-test/public-walk-is-callable-through-an-agent-sci-eval`, keeping its
  direct/web plan-reuse assertions. Its `with-redefs` stays under task class `seon.test.edn` (about 300
  with-redefs sites), because the conversion is larger than 15 minutes;
- `shown-text-test` evaluates without the walk binding.

## Slice B (after loop-pass releases turn.clj)

See the AFTER-SEAM rows above.

### Slice A design check

- The design held. `(seon.render/walk)` on default answered the error "No calling agent is bound to this evaluation."
  (agent walk-delete, 0 ms), because nothing in production binds `*walk-context*`, so the tool was already dead to
  agents. The namespace `seon.render.walk` stays: namespace pages, entity pages, value continuation and related
  views use its acquisition and graph. Only the agent tool and its ambient custody went.
- Awkward: the eval test that proved the SCI walk also proves that root acquisition and the web share a pull plan,
  using `with-redefs` (task class in `seon.test.edn`, about 300 sites). The SCI leg went and the plan-reuse
  assertions stayed. Converting the `with-redefs` is larger than 15 minutes, so the test is not converted here.
- Schema: `:seon.render.walk/branch` (the optional unit member) had no producer and was removed. The historical
  baseline in `test/seon/schema/datahike_parity.edn` still names it. That is correct: it is the historical oracle,
  and it compares only unchanged forms.
- Net: src -168 +1, test -24 +4, schema -4.
