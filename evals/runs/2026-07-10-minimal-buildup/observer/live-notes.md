---
type: research
status: active
tags: [research, agent]
---

# Live observer notes — rung-0 drives, min-a cluster (2026-07-10)

Dedicated read-only observer for the minimal-context + namespaces-cards
drives. Question under test: is the context CLEAR? Every confusion below
maps to the responsible BLOCK and a candidate reword of that block's own
lines (colocation rule).

## Drive 1 — two-bucket-ns-d1 · agent `phy-2607101442` · dispatched 18:42Z

- **Outcome:** SUCCESS — 9/9 pytest green, `(complete …)` accepted, turn 14,
  ~5.5 min wall. Observer attached at turn 13 (drive nearly done); full
  transcript recovered from `/agent/phy-2607101442/debug`, so the per-turn
  table below is byte-exact but reconstructed, not caught live.
- **Headline:** the task succeeded, but the transcript is DENSE with
  fabrication — invented file-shas, invented job-ids, invented result-ids,
  narrated test results never seen, and one flatly hallucinated
  "two candidates" response. The errors-as-data surface caught every one
  and the agent recovered each time within 1-2 evals. The context is
  RECOVERABLE more than it is CLEAR.

### Confusion table (turn ≈ eval sequence; ids are the `result/<id>` refs)

| # | Where | Symptom | Evidence (verbatim) | Responsible block | Candidate reword |
|---|-------|---------|---------------------|-------------------|------------------|
| 1 | GCN/OBO | **Fabricated file-sha** — passed `:seon.agent.fs/file-sha "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0"` (a placeholder, twice). The prior `fs/view` display was clipped at 50 tokens so the sha was never VISIBLE; agent invented one instead of binding the result. | `"file changed since your read — the on-disk SHA is f1b6e41c9dea04b24…"` | fs/view response render under truncation + the result-truncation tail | Truncated `fs/view`/`read-file` displays should ALWAYS surface `::file-sha` (tiny, load-bearing) before clipping `::content`. Alternatively the truncation tail could say "keys you did not see are NOT known to you — bind, don't guess". Note the sha-mismatch ERROR text (which prints the real sha) is what rescued the agent — good colocation, keep it. |
| 2 | after OBO | **Hallucinated result** — prose: `; Two candidates — line 11 and line 16. I want line 16. Let me add :near:` — the actual result was the sha error again; NO candidates response ever existed. Agent pattern-matched the drive prompt's "if replace! returns candidates…" clause into a fake observation. | line 557 of stripped transcript | fabrication (Mode B, prose claiming unseen results); partly the DRIVE PROMPT priming a scenario | Observer-only note: system-text line "never write out a result yourself — a result you type is stripped" covers typed values but not PROSE CLAIMS about results. Candidate addition to that block: "and never describe a result you have not seen — the next turn's real value contradicts you". |
| 3 | near-16 | **`:near` shape confusion** — `:seon.agent.fs/near 16` → malli: `expected [:tuple :int :int] … got 16`. The DRIVE PROMPT itself teaches `:near <line>` (a bare line number); the schema wants a tuple. | `✗ malli/instrument-input seon.agent.fs/replace! arg 0 … at [0 :seon.agent.fs/near]` | **card/prompt mismatch** — `::replace-request` card shows `::near :seon.agent.fs.match/near` (opaque alias); the `:seon.agent.fs.match/near` shape is not visible on the replace! card | Either accept a bare int in the schema (`[:or :int [:tuple :int :int]]` — friendlier) or render aliased shapes inline on the request card so `[:tuple :int :int]` is readable before the first call. Also fix the drive-prompt wording (`:near <line>` implies an int). |
| 4 | line 568 | **Claimed success after an ERROR** — right after the malli `:near` failure: `; Good, line 16 is fixed.` Nothing was fixed. | line 568 | same fabrication family as #2 | covered by #2 reword. |
| 5 | multiple | **Prose leaking as bare forms** — 4 distinct READ ERRORs / prose-nils: a line starting `: goalBucket…` (single-colon), `(the \`invalid\` state check…)` → nil, a syntax-quote parse error on `` (`"one"` or `"two"`) ``, a ```` ```python ```` fence. Each got the repair-note teaching. | `✗ READ ERROR — [line 1, col 3] A single colon is not a valid keyword.` etc. | REPL grammar block ("Write the forms you want run…") — the `;`-prose rule isn't holding when the agent thinks in markdown | The grammar block teaches what IS evaluated but not the failure mode. Candidate line: "Every non-`;` line is code. Markdown (```` ``` ````, backticks, `:` bullets) is NOT understood — prefix every prose line with `;`." |
| 6 | Kqp/lvX/CtK, VzV/pvs, iuY, DQV/RvO | **Fabricated job-ids, 3 separate rounds** — polled `job-9a2b3c4d`, `job-9e3f1a2b`, `job-abc123de`, `job-def456gh`, none ever issued. Pattern: agent writes `run-bg!` AND its poll calls in the SAME turn, so it must invent the id (the real id arrives only with next turn's results). 8 wasted evals. | `"no background job \"job-9a2b3c4d\" — it never started…"` | system-text "never write out a result yourself" + shell/run-bg! card — neither says "the job-id is a RESULT; you cannot poll in the turn that launches" | On `::run-response`/run-bg! docstring: "The job-id arrives in the result — launch, END your turn, poll next turn with the id you were given." This is the single highest-frequency confusion in the drive. |
| 7 | line 843 | **Narrated test results never seen** — `; Test 1 passes now! Test 2 fails with "No more moves!"` — at that point the only job-output attempts had failed on fabricated ids; no pytest output had been displayed. (The named failure was a lucky/derived guess; the claim form is pure fabrication.) | line 843 | fabrication Mode B again | covered by #2 reword; strongest single exhibit for it. |
| 8 | emJ / UVz | **Fabricated result-ids** — `result/emJ-2607101443` (never existed; auto-fix refused, 5 candidates) and `result/UVz-2607101443` (not live). | `✗ result/emJ-2607101443 is not defined…` | result-ref grammar block | The bare-⟹ grammar line could add: "result ids are ASSIGNED to you — only reference ids that appear after ⟸ in this log." |
| 9 | line 660 | **`…` elision copied into code** — `✗ 17× \`…\` is not defined … 17 consecutive failures collapsed`. Agent re-emitted a clipped display (with `…⟨⟩` elision marks) as code. | line 660 | truncation-display marker | The `⟨⚠ TRUNCATED…⟩` note says "the live value is COMPLETE" but not "the `…` is a display mark, not code — never copy it". Add that clause. Collapsing 17 failures into one line worked well (kept context clean). |
| 10 | chX/tBp | **Contradicted a live result** — `(clojure.string/includes? … "goalBucket") ⟹ false` then next line `; It exists. Let me find it:`. Two evals later it accepted reality. | line 662-663 | not a block defect — model-side result-reading lapse | none; log as fabrication-adjacent. Recovery was fast because the follow-up query also returned `()`. |
| 11 | whole drive | **Heredoc instruction ignored** — drive prompt step 3 mandated `#code/python <<END … END` literals; agent used plain escaped strings everywhere (and eventually bare multi-line strings in `write-file`, which DID work). | — | drive prompt / edit-protocol adoption, not a context block | Edit-protocol lane datum: heredoc uptake = 0 for this drive. Plain strings sufficed once it switched to `write-file` + line-anchored `edit-file`. |
| 12 | ulC | **Anchored-edit near-miss** — `edit-file` from-line 50/to-line 50 REPLACED intent but INSERTED (`::lines-inserted 4`) leaving the old return lines as dead code below; agent only discovered the mangled file two evals later via `read-file`, then gave up on surgical edits and rewrote the whole file (twice). | `{:seon.agent.fs/lines-inserted 4 …}` | `::edit-response` card / edit-file semantics | If from/to-line given and content supplied, is insert-vs-replace ambiguous? Response said `lines-inserted` with no `lines-replaced` — the agent read it as success-as-intended. Card/docstring should make replace-vs-insert explicit in the response keys the agent actually sees. |

### What WORKED (evidence the cards + errors-as-data earn their keep)

- `run-bg!` wrong key `::command` → malli hint `did you mean :seon.agent.shell/cmd?` → fixed in ONE retry. Card+hint = working as designed.
- sha-mismatch error printing the REAL on-disk sha rescued the fabricated-sha spiral immediately.
- The auto-fix "refused (ambiguous): 5 near matches" on the fabricated result-id correctly declined to guess.
- 17-consecutive-failure collapse kept the fabrication from flooding context.
- No error-bounce loop ever exceeded ~3 shapes on the same verb; no complete-instead-of-debug (complete was called exactly once, green); no dawdle (every turn advanced); no ns confusion.

### Running verdict on "is the context CLEAR?"

**Provisionally: clear enough to recover, not clear enough to prevent.**
Strongest evidence FOR clarity: zero call-shape errors on grep/view/job-status
first tries; the one wrong-key call self-corrected in one eval off the malli
hint; task green in 14 turns. Strongest evidence AGAINST: the agent
fabricated at every RESULT boundary — file-sha, job-id, result-id, candidate
lists, test outcomes — i.e. the grammar teaches "don't type result VALUES"
but not "don't claim result KNOWLEDGE." The dominant candidate fix is one
added sentence to the system-text results rule (#2/#6/#8 above) plus
surfacing `::file-sha` under truncation (#1).

## FLAG for runner

None — drive 1 completed green on its own. No intervention needed.

## Status

- 18:52Z drive 1 idle/complete; watching `logs/pod-min-a.log` for the next
  dispatch (new agent id on `seon.agent.turn/` lines).
