# Dogfood through the server, docs in the db, references on the screen (2026-10-25)

A working paper for root (handoff 2026-10-25, "New ideas" 4–6). Measurement and design only, no code. Logs and detail are in `notes/dogfood/` (summary.md, unrun-proof.md, long-name.md, docs-audit.md, screen-reads.md).

## 1. Dogfood: three Claude subagents as Seon agents

**Setup:**
- A scripted root on this worktree's ports (8240): no model calls, $0.
- Three fit requests from `seon.fixtures/fit-requests`, each created as a held design task, each with one agent:
  - `seon.ref/a-summary-says-what-it-left` (Sonnet);
  - `seon.context/a-proof-that-never-ran-says-so` (Sonnet);
  - `seon.print/a-long-name-cut-says-its-chars` (Haiku).
- Each agent had `docs/prompt.md` as its system prompt and worked only through the server ops: attach, context, eval, done. They read no repository files.

**Outcome:** none of the three finished the design step under the done rule. All three wrote red proofs and a rewritten plan, in 25–29 evals each, against a 30 eval budget.

**Gaps, ranked by evals lost and whether they block.** I checked each against the code.

1. **A design step on a request cannot register its proofs (blocks; 3 of 3 agents).**
   - The fit requests carry no proofs (`seon.fixtures/fit-requests`: "design writes its own proofs").
   - `my.task/update!` takes only `:seon.task/plan` and `:seon.task/instructions` (its contract), and no agent-facing function writes `:seon.task/proofs`.
   - So every deftest the agent writes is a `:stray-red` (`seon.reconcile/not-done`), and the refusal carries no `:do`, unlike `:no-design` and `:proof-unknown` (`seon.context/refused`).
   - The design instructions (`seon.template/instructions`) say "the proofs … written as deftests and run red" and stop there.
   - Fix at the producer, one of:
     - `update!` also takes `:seon.task/proofs` (`(my.task/update! :seon.task/proofs conj 'ns/t)`);
     - or the judge counts a design run's new red deftests in the task's namespace's test ns as its proofs.
   - In both cases the instructions name the form, and `:stray-red` gets a `:do`. This is a ruling on how a design adds proofs.
2. **An eval answers only its last form's value, while the prompt says "each form's value" (3 of 3; about 2–3 evals each).**
   - Verified: `(+ 1 2) (str "a" "b")` answers `:value "ab"` only.
   - A form that throws also hides the values before it.
   - Fix: the producer answers each form's value, or the prompt says "the last form's value; `prn` the others". The producer is better, since it matches nREPL.
3. **A one-line request has nothing to start from (3 of 3; about 10 evals each, the biggest cost).**
   - The story's write set is the plan's cited vars, with source and callers (`seon.context/run-state`), and a user's line cites none.
   - `:similar` is 0, so nearest-by-meaning is off.
   - `my.agent/grep` answers about 30 unranked names, with no lines, at about 350 ms and 240 MB a call.
   - Agents walked `callers`, `source` and `dir` by hand to find the producers.
   - See §3 for the pull.
4. **The proof lifecycle is unreadable at design (2 of 3).**
   - `(my.task/status)`, the form the prompt names, answers `:seon.turn/not-observed` until a model turn has run. Under the outside agent there never is one.
   - `(my.agent/prove)` answered `:ran 0 :deferred [...]` for a new test it classed as a process test, with no reason given.
   - `done` answers only `{:judging …}` and ends the agent, so no agent saw a verdict.
   - Fix:
     - status observes when first called;
     - `:deferred` names the process var each test reaches;
     - `done` answers the judge's reasons, or the form that reads them.
5. **Help and schemas don't answer a miss (1 to 2 of 3; about 5 evals).**
   - `(my.agent/help "references")` answers nil.
   - A cited section address read through `seon.docs/view` answered `:no-lead`.
   - No form shows a registered schema's form and its owning file. `m/form` and the registry lookups the agent tried all failed.
   - Fix: help lists its topics on a miss, and one documented form reads any wiki address and any schema.
6. **Smaller:**
   - The outside agent's `:context` is the old block (`seon.turn/context-block`: story plus turn sections), not what a model is sent (system prompt and screen). So outside agents dogfood a different screen than models get. It should print `seon.context/parts-of`.
   - The cost-proof rule doesn't say whether a function whose work is capped by a budget needs a proof.
   - `in-ns` persists across evals and makes `doc` unresolved.
   - `(clojure.test/run-tests 'ns)` is the only run form agents found: 36 tests, about 7 s, about 3 GB.
   - Attach answers `:a2` while the ops want `a2`. That is my shell helper's quoting, not the server.

**Finding for the `a-proof-that-never-ran` request itself:** a test run records only how many tests ran, not which ones finished. So "ran with no assertions" and "never ran" can't be told apart by changing the status alone; the test run's data has to change.

## 2. Docs in the db: audit

Measured on this worktree's root; code is cited by var.

**Mirroring:**
- `seon.docs/load-tx` mirrors every page at boot and on `my.root/load-docs!`, not when a file changes.
- All 33 pages and 364 sections are in the db, matching the 364 headings on disk.
- Schema: `resources/seon/schemas/seon.section.edn`. A section has address, heading, lead, text, parent, references, names, dead-links, read, baseline-unread and reviewed.

**Citations:**
- They are read by `seon.ref/parse` and resolved at load (`seon.docs/citation-target`) to a section, a var or a dead link.
- There are about 380 resolved edges and 0 dead links.
- One citation is unresolved: `plan.md#waiting-on-the-owner` → `my.agent/message!`, reported by `seon.problems/dead-citations`.

**Stale sections:**
- `seon.problems/stale-sections` marks a (section, var) pair stale when the cited var's form digest differs from the one at the section's last commit. It also counts vars that were absent then, and sections whose git baseline couldn't be read.
- So the 223 on main are counted per pair, and any edit to a widely cited var stales every section citing it.
- That is real drift, but it counts code churn, not wrong prose.
- This root has no baseline (0 stale), so I did not split main's 223 by kind. I didn't query the owner's root (7888), per the brief.
- Split them by kind: absent, digest changed, no baseline.
- Ruling to ask for: does a section go stale when its cited var's *contract or docstring* changes, rather than any byte of its form? That would cut the noise without hiding drift.

**Levels of detail already exist:**
- `seon.docs/view` takes three depths, `:summary`, `:doc` and `:code` (`seon.ref/shown`).
- The summary is the section's first lead sentence (`seon.ref/lead-sentence`); 54 of 364 sections have no lead.
- Readers are `docs/view` and `docs/cited-view` (story and walk), `page-text` (system prompt, help) and `my.agent/help`.
- No section render is declared on a schema, and the UI has no docs page.

**Design (no new attribute):**
- A section at three levels is `docs/view` at `:summary` (the lead sentence), `:doc` (the text), and the body plus `:references` and `:cited-by`. The last two come from the existing `:seon.section/references` and the reverse edge.
- Add those two keys to the `:seon.ref/view` schema and fill them in `docs/view`.
- Give `docs/page` a depth argument.
- Fold `my.agent/help`'s own shaping and `docs/section` into `view`, so one producer serves the story, help and the UI. The UI page is then a render of `view`.
- This replaces `help`'s shaping and `docs/section`.
- Before it, give the 54 sections their leads (a wiki edit), and get the stale ruling.

## 3. References on the screen

**What the story pulls today** (`seon.context/run-state`):
- the plan's cited vars, with their source and callers (`docs/callers`);
- cited sections at their delimiter's depth;
- each proof's run form;
- `:similar` (0, so off).

The screen adds status and changes. There is no write set beyond what the plan cites.

**Measured.** The step 6 stores are gone, and `notes/measure/step6/` keeps only per-run counts, with no eval forms. The forms that survive are the last requests of 8 earlier runs (`notes/requests/*.json`: 4 tasks × `base3` and `prose1`). Those hold 120 eval calls and 143 read forms; 102 of the 120 calls read something (detail in `notes/dogfood/screen-reads.md`):
- **12% (17)** were already shown by today's story: a cited var's source, and the names of its callers.
- **38% (55)** could be pulled from what the story already knows:
  - a caller's or callee's source one hop away (17);
  - the proof's test namespace (7; read in 7 of 8 runs);
  - a sibling test on the call graph (5);
  - schemas (7, all in one run);
  - file hunts (17, 16 of them one run chasing a stale task, which takes the figure to about 31%).
- **50% (71)** was exploration no rule predicts: grep, other vars, whole namespaces.

From step 6's own counts: only 10–19% of evals cited a reference in their prose. `:similar` was named in 23% of evals, but those were the functions the runs without it found, so it saved no reads.

**Not measured:**
- step 6's own forms;
- how each read was used;
- what each turn's screen showed;
- any counterfactual run.

A first answer from the same measuring agent claimed counts over 47 step 6 runs and a `write-set-refs` at `context.clj:1004`. Neither exists (the file has 590 lines, and the data has no forms), so I dropped it.

**Proposed pulls,** each paying only if agents would read it with probability above about turns left / turns:
1. **The proof's test namespace,** its ns form and helper names, in the story: about 220 tokens a turn, saving about 0.9 reads a run.
2. **The `my.*` functions the system prompt names, with their docstrings and contracts,** in the help page: sent once and cached, against `(source my.task/status)` and `(doc my.task/update!)` reads at design, which appeared in the dogfood runs too.
3. **For a plan that cites nothing,** a seeded write set: `:similar` on for `:design` tasks only, or `my.agent/grep` ranked by definition name. About 200 tokens a turn at design. This attacks dogfood gap 3, the largest cost, and must be measured, since step 6 showed `:similar` saving nothing at implement.
4. **The call-site line beside each caller name,** and callees as one line each (name, arglists, first sentence), not their source: up to about 480 tokens a turn, saving about 1 read a run.
5. **The nearest example test on the call graph,** cut to 40 lines through the printer: about 350 tokens, saving about 1 read a run.

Skip schema forms and file hunts. Pulls 1, 4 and 5 come to about 1,050 tokens a turn, against roughly 3 of 13 reads a run.

What I'd do first: ruling 1 (how a design adds proofs) and gap 2 (each form's value) block or mislead every design run. Then the pulls run as one variant on the fit requests (scripted first, then a paid rerun of step 6's 5 × 3 on root's go), keeping the eval forms this time.
