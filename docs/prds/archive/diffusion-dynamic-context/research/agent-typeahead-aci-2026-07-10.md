---
type: research
status: active
tags: [research, agent]
---

# Agent typeahead — ACI prior art, selection tokens, typed holes, plan protocols, uncertainty-as-UI

**TL;DR:** The literature strongly supports the glyph-menu design as measured
in [typeahead-hole-filling-2026-07-10](typeahead-hole-filling-2026-07-10.md),
with one giant caveat and one clear differentiator.

- **Support:** SWE-agent's ACI thesis (concise feedback + guardrails +
  consolidated actions = +8.7pp absolute; lint guardrail alone worth ~3pp) and
  AgentOccam (+161% over a plain agent from ONLY re-aligning observation/action
  space with pretraining) both say interface shape dominates agent quality.
  Constrained/enumerated action spaces measurably reduce hallucinated actions
  (LASER, schema-validated tool calling).
- **The giant caveat:** *forced* selection is where everything documented goes
  wrong — MCQ selection/position bias (13–85% swings under option reordering),
  hallucinated arguments under `tool_choice: required`, the "constraint tax"
  (tool-calling suppression + >30% reasoning degradation when grammar masks
  interrupt reasoning), and "Let Me Speak Freely" (10–15% reasoning drop in
  hard JSON-mode). Our protocol's "glyph optional, free-typing always works"
  property is exactly the mitigation the mitigation-literature converges on
  (loose formats, think-then-constrain). **Never make the menu mandatory.**
- **Differentiator:** nobody reads the *posterior over the selector tokens* as
  a calibrated intent signal while leaving generation free — the closest
  analogs are speculative-decoding acceptance predictors (draft entropy/margin
  → acceptance, strongly monotonic) and Copilot's confidence-gated display
  filter. Our round-4 measurement (none-of-the-above collapse to −22…−28) is
  the same signal class, used as UI. Novel and defensible.
- **For the code-canvas FSM:** Hazel/Hazelnut gives the theory (every edit
  state statically meaningful; holes as the totalizer; a 4-action edit
  calculus: `move`/`construct`/`del`/`finish`); tylr gives the practical
  middle (token-level editing + "structural obligations" naming exactly what
  delimiter is owed); parinfer gives the cautionary tale (smart mode died on
  integration, pick ONE inference direction). Our clamp/hole canvas is a
  Hazelnut-style editor where the model is the user.
- **For planning:** every serious harness converged on the same shape — a
  small persistent checklist with a 3–4-value status enum
  (`pending/in_progress/completed(/cancelled)`), *re-rendered into context
  each turn with completed items dropped* (hermes-agent `todo` tool, Voyager's
  completed/failed task lists, letta core memory). ReWOO shows plan-first is a
  token win (5× cheaper, +4% HotpotQA) but pure plan-then-execute WITHOUT
  runtime re-adjustment is catastrophic on dynamic tasks; granularity
  literature: too-coarse plans starve the executor, too-fine plans bloat
  tokens. Glyph-marking plan items done/current is a cheaper write-path onto
  this settled render-shape.

Ranked recommendations at the end.

---

## Q1 — ACI design: SWE-agent and what followed

### SWE-agent (Yang, Jimenez et al., NeurIPS 2024)

Paper: [arXiv:2405.15793](https://arxiv.org/abs/2405.15793) ·
[NeurIPS PDF](https://proceedings.neurips.cc/paper_files/paper/2024/file/5a7c947568c1b1328ccc5230172e1e7c-Paper-Conference.pdf) ·
[ACI docs page](https://swe-agent.com/0.7/background/aci/) ·
vendored at `/Users/sean/src/seon/reference-code/swe-agent/`.

The four ACI properties (paper §2, paraphrase of search-verified summaries):

1. **Actions simple and easy to understand** — few options, concise
   documentation; reduces need for demonstrations/finetuning.
2. **Actions compact and efficient** — consolidate important operations
   (navigation, editing) so one action = meaningful progress.
3. **Environment feedback informative but concise** — substantive state +
   effect of the last action, no dumps.
4. **Guardrails mitigate error propagation** — e.g. a syntax checker at edit
   time helps agents recognize and quickly correct mistakes.

Verbatim from the docs page (fetched 2026-07-10):

> "We add a linter that runs when an edit command is issued, and do not let
> the edit command go through if the code isn't syntactically correct."

> "When commands have an empty output we return a message saying 'Your
> command ran successfully and did not produce any output.'"

Numbers (from the paper, via search-verified summaries): 12.47% SWE-bench
resolved vs 3.8% prior best (GPT-4 Turbo base); ablations: removing the
purpose-built edit action costs **−7.7pp**, removing the lint guardrail costs
**~−3pp**. The headline claim: *ACIs tailored for LMs outperform interfaces
designed for humans (the plain Linux shell)*.

Vendored evidence of the guardrail actually used: the
`windowed_edit_linting` tool
(`reference-code/swe-agent/tools/windowed_edit_linting/config.yaml`) — the
edit signature is line-ranged replacement with an explicit `end_of_edit`
terminator and the docstring warns "THIS COMMAND REQUIRES PROPER INDENTATION"
— i.e., even the *docstring* is part of the guardrail.

**Mapping to us:** the diffusion canvas's clamps are a *stronger* form of the
lint guardrail — the SWE-agent linter rejects a bad edit after the fact; a
clamp makes the bad edit unrepresentable. Our oracle repair loop is their
"hasten recovery" principle mechanized.

### AgentOccam (Amazon Science, 2024) — interface alignment dominates

Paper: [arXiv:2410.13825](https://arxiv.org/abs/2410.13825) ·
[GitHub](https://github.com/amazon-science/AgentOccam).

No new planning/RL machinery — only refined observation + action space to
align with what the LLM was pretrained on: removed non-essential actions,
abstracted low-level operations, restructured observations into concise
Markdown. Result on WebArena: **+9.8 absolute (+29.4%) over prior SOTA** and
**+26.6 points (+161%) over a similar plain agent**. Quote (via search):

> "underlines the critical role of carefully tuning observation and action
> spaces for LLM-based agents … misalignment between a web agent's
> observation and action representation, and the data on which the agent's
> underlying LLM has been pre-trained."

**Mapping to us:** the strongest published support for "render the menu in a
form the model already speaks." Enclosed-number glyphs ①② are common in
pretraining corpora as list markers — the round-4 result (model emits ①
unprompted at p≈1.0) is an alignment win of the AgentOccam kind.

### Menus / enumerated actions vs free-form — the evidence

- [LASER (arXiv:2309.08172)](https://arxiv.org/pdf/2309.08172) — models web
  navigation as a state machine with a per-state *enumerated* action set;
  selecting from valid actions for the current state outperforms free-form
  ReAct-style generation on WebShop precisely because invalid actions are
  unrepresentable.
- [CodeAct (Apple ML Research)](https://machinelearning.apple.com/research/codeact)
  — the counter-pole: agents act *better* generating executable code than
  emitting JSON tool calls, because code is the pretraining-native action
  representation (up to ~20% higher success in the original paper). This is
  the published justification for our "free-typing Clojure always works" arm.
- Agentic-RL survey ([arXiv:2509.02547](https://arxiv.org/pdf/2509.02547)) +
  agent-systems survey ([arXiv:2601.01743](https://arxiv.org/html/2601.01743v1)):
  the field's consensus framing — "structured action spaces (typed tool
  schemas and structured outputs) as the primary control surface: the model
  proposes actions that must pass schema validation … reducing the impact of
  free-form hallucinations."
- Grounding-with-RL ([GLAM, arXiv:2302.02662](https://arxiv.org/pdf/2302.02662)):
  larger action spaces degrade some models and not others — action-set SIZE
  is a per-model tunable, keep menus short.

**Synthesis:** the literature is not "menus beat free-form" or vice versa —
it is *bimodal*: enumeration wins where the legal set is small and
DB-derivable (LASER, schema-validated calls), code-generation wins where the
task is open-ended composition (CodeAct). A protocol offering BOTH, with zero
mode switch, sits exactly on the published Pareto frontier. No prior work we
found offers both simultaneously with the selection channel being *optional
single tokens inside an otherwise free canvas* — that combination appears
novel.

---

## Q2 — Selection-token / control-token prior art and failure modes

### Prior art

- **Toolformer** ([arXiv:2302.04761], via
  [TDS explainer](https://towardsdatascience.com/toolformer-guiding-ai-models-to-use-external-tools-37e4227996f1/)):
  the canonical special-token trigger — `[` / `<API>` starts a tool call;
  generation is intercepted at the trigger token, the API executes, the
  result is spliced in, decoding resumes. Notably: "tool calling can be
  disabled by manually setting the probability of the `<API>` token to 0
  during decoding" — logit-mask control of the affordance, the same
  mechanism class as our glyph masking/banning.
- **Provider `tool_choice` mechanics**: OpenAI `tool_choice: "required"`
  ([community announcement](https://community.openai.com/t/new-api-feature-forcing-function-calling-via-tool-choice-required/731488));
  Anthropic `{"type": "any"}` / `{"type": "tool", "name": …}`
  (via [function-calling guide](https://tokenmix.ai/blog/function-calling-guide)).
  Both are *grammar-side forcing* — the provider constrains decoding to a
  tool-call prefix.
- **letta / MemGPT `request_heartbeat`**
  (`reference-code/letta/letta/constants.py:217-218`): a boolean *control
  parameter injected into every tool schema* — the model sets it to keep the
  turn. Verbatim:

  > "You MUST set this value to `True` if you want to send a follow-up
  > message or run a follow-up tool call … If set to `False` (the default),
  > then the chain of execution will end immediately."

  Precedent for a single-datum control channel riding alongside content —
  and it works in production. Our glyph is the same idea moved from a JSON
  field to a vocabulary token (cheaper: zero schema, readable at the logit
  level).
- **hermes-agent `clarify` tool**
  (`reference-code/hermes-agent/tools/clarify_tool.py`): the agent presents
  ≤4 predefined choices; the UI *always appends a 5th "Other (type your
  answer)" option*. Even a human-facing menu in a production harness ships
  with the escape hatch built in — convergent with our "or type any Clojure."

### Documented failure modes (design against ALL of these)

1. **Hallucinated arguments under forced selection.** With
   `tool_choice: required`, "models make up values for required parameters"
   ([OpenAI community report](https://community.openai.com/t/forced-function-calling-making-up-values-for-required-parameters/931947));
   generally "models can hallucinate tool names, drop required arguments, or
   emit malformed JSON" ([reliable tool calling](https://changegamer.ai/resources/reliable-tool-calling));
   strict schema mode cuts wrong-type/missing-field errors that otherwise run
   2–5%. **Forcing selection when no option fits manufactures a selection.**
   Our none-of-the-above posterior collapse is the antidote — but only if the
   protocol never *requires* a glyph.
2. **Constraint tax / premature triggering.**
   [Constraint Tax in Open-Weight LLMs (arXiv:2606.25605)](https://arxiv.org/pdf/2606.25605)
   — structured-output constraints *suppress tool calling*;
   [Thinking Before Constraining (arXiv:2601.07525)](https://arxiv.org/abs/2601.07525)
   — "premature triggering … constrained decoding interrupts ongoing
   reasoning … over 30% degradation" on math-heavy tasks; masks that force
   the model off its top-10 tokens degrade quality
   ([constrained-decoding overview](https://mbrenndoerfer.com/writing/constrained-decoding-structured-llm-output)).
3. **Format restriction hurts reasoning.**
   ["Let Me Speak Freely?" (arXiv:2408.02442, EMNLP-Industry 2024)](https://arxiv.org/abs/2408.02442):
   hard JSON-mode costs **10–15%** on math/symbolic reasoning vs free-form
   (while *helping* classification); looser restrictions and two-step
   NL-then-format recover it. Classification-vs-reasoning split matters for
   us: glyph selection IS a classification act (menus fine, maybe better);
   code synthesis is reasoning (never constrain it to the menu).
4. **MCQ selection/position bias — the direct threat to glyph menus.**
   [Order sensitivity (arXiv:2308.11483, NAACL Findings 2024)](https://arxiv.org/abs/2308.11483):
   **13–85% performance swings** across option reorderings; bias concentrates
   when the model is uncertain between top choices.
   [Strengthened Symbol Binding (arXiv:2406.01026)](https://arxiv.org/pdf/2406.01026):
   "selection bias makes models choose a preferred option symbol instead of
   one corresponding to the correct option" — weak content↔symbol binding.
   Debiasing exists (PriDe token-prior estimation, permutation averaging —
   [order-independence w/o finetuning, arXiv:2406.06581](https://arxiv.org/pdf/2406.06581)).
   **Consequences for us:** (a) glyph posteriors carry a per-position prior —
   calibrate/subtract it (measure p(①…⑩) under a content-free menu, once per
   model); (b) don't trust rank differences smaller than the position prior;
   (c) for high-stakes menus, permute or re-score with candidates swapped;
   (d) keep menus ≤ ~5 items where binding is strongest.
5. **Over-selection / sycophantic menu use.** Not crisply named in the
   literature, but implied by (1) and (4): a rendered menu invites emission
   even when wrong (our own two-bucket observer notes recorded fabrication at
   result boundaries — same grammar-invites-behavior mechanism, per the
   repo's DO-NOT-WRITE-HACKS history). Mitigation is mechanical, not
   persuasive: the calibrated none-of-the-above threshold gates *acting on*
   a glyph, regardless of whether one was emitted.
6. **Special-token leakage.** Round 4 already observed a `<|channel>thought`
   scaffold token leaking into free space — same class as documented
   grammar/control-plane vulnerabilities
   ([arXiv:2503.24191](https://arxiv.org/pdf/2503.24191)). The one-line logit
   ban at free positions is the fix; keep it.
7. **Mode collapse under repetition** — template repetition/semantic looping
   even at healthy entropy
   ([arXiv:2605.00435](https://arxiv.org/html/2605.00435)); relevant if the
   same menu renders every turn: watch for the model degenerating into
   glyph-only replies. Detection: glyph emission rate per turn window;
   mitigation: drop the menu when the last N turns were all selections.

---

## Q3 — Structured editors and typed holes: grounding the canvas FSM

### Hazelnut / Hazel (Omar, Voysey, Hilton, Aldrich, Hammer — POPL 2017; Hazel live semantics — POPL 2019)

- [Hazelnut: A Bidirectionally Typed Structure Editor Calculus (POPL 2017)](https://arxiv.org/abs/1607.04180)
  ([official PDF](https://plv.colorado.edu/papers/hazelnut-popl17.pdf), Agda-mechanized)
- [Live Functional Programming with Typed Holes (POPL 2019)](https://par.nsf.gov/servlets/purl/10109143)
- [hazel.org](https://hazel.org/)

Core theory (search-verified): expressions and types with holes (H-expressions
/ H-types); the action semantics "maintains the invariant that **every edit
state is a statically meaningful (i.e. well-typed) term**"; type-inconsistent
terms are automatically wrapped in a hole ("safely defers the type
consistency check until the term inside the hole is finished"). The POPL 2019
paper extends this to *evaluation*: programs with holes can still RUN,
producing indeterminate results around hole closures — fill-and-resume.

**The minimal edit-state/action set** (Hazelnut's action calculus — this is
the answer to "what is the minimal set of edit states they enumerate"):

- A cursor (the Z-structure: exactly one focused subterm).
- Two hole kinds: **empty holes** (missing term) and **non-empty holes**
  (present but type-inconsistent term, kept inside hole brackets).
- Four action families: **`move`** (child n / parent), **`construct shape`**
  (insert a form at the cursor), **`del`** (replace focus with an empty
  hole), **`finish`** (commit a hole's contents once consistent).

Sanity properties: actions are total (every action sequence yields a
well-typed state), movement is meaning-preserving, and *constructability* —
every well-typed term is reachable by some action sequence.

**Mapping to us:** the diffusion canvas IS a Hazelnut editor with the model
as the actor: clamp = finished/committed region; hole = empty hole;
oracle-flagged near-miss span = non-empty hole (present, provably
inconsistent, kept editable); repair/scramble = `del` + re-`construct`
localized to the hole; lock-and-harvest = `finish`. Adopting the two-hole
distinction is the actionable import: today's loop conflates "not yet
generated" with "generated but failing" — Hazelnut says give them different
statuses (and the FSM different transitions).

### tylr (Moon, Blinn, Omar — TyDe 2022 / VL/HCC 2023)

- [tylr: a tiny tile-based structure editor (TyDe 2022)](https://hazel.org/papers/tiny-tylr-tyde2022.pdf)
  ([DOI](https://dl.acm.org/doi/10.1145/3546196.3550164))
- [Gradual Structure Editing with Obligations (VL/HCC 2023)](https://hazel.org/papers/teen-tylr-vlhcc2023.pdf)
- [github.com/hazelgrove/tylr](https://github.com/hazelgrove/tylr) — now the
  editing substrate inside Hazel.

Key ideas (search-verified): token-level linear editing that "ensures
manipulated tokens can always be parsed back into a well-formed AST";
near-arbitrary range selections including cross-tree spans; **gradual
structure editing** — the user may *locally break* tree structure, and the
editor tracks **"structural obligations"**: "given an ill-formed program,
encode where certain syntactic delimiters must be inserted in order to yield
a well-formed program."

**Mapping to us:** structural obligations are exactly the datum our
boundary-artifact problem needs — instead of "parse failed," the oracle can
carry *which delimiter is owed where* (off-by-one paren, suffix echo) as an
obligation attached to the hole, making repair targeted rather than
scramble-everything. Also the license for cross-tree spans: hole boundaries
need not respect AST boundaries if obligations are tracked.

### Pantograph (2024)

[Pantograph: A Fluid and Typed Structure Editor (arXiv:2411.16571)](https://arxiv.org/pdf/2411.16571)
— newer point in the same space (typed + fluid); worth a skim if the FSM
grows type-directed transitions, not load-bearing for v1.

### parinfer — the cautionary tale

[Parinfer](https://shaunlebron.github.io/parinfer/) ·
[smart-mode notes in parinfer.js docs](https://github.com/parinfer/parinfer.js/blob/master/doc/code.md).

Two total modes: **Indent Mode** (user owns indentation, editor infers
parens) and **Paren Mode** (user owns parens, editor infers indentation).
**Smart Mode** — auto-choosing between them per edit — "worked great in
sandboxes" but died on integration: "the majority of editor APIs do not
allow safe integration of Smart Mode's rules … editors may miss certain
changes like search/replace operations, leading to incorrect decisions";
implementations in Cursive/Vim/Atom/Emacs proved "very difficult and
ultimately incomplete."

**Lesson:** an inference rule that needs to *classify the edit* to decide
which invariant to preserve is fragile at the seam; a rule that always
preserves ONE declared invariant is robust. For the canvas: fix the
authority direction per interaction (clamps are always authoritative; free
text always yields), never infer per-edit which side wins.

### Cursorless — glyph-targeted structural editing (direct visual prior art)

[cursorless.org](https://www.cursorless.org/) — spoken structural editing on
tree-sitter scopes where every token on screen is decorated with a small
colored "hat" glyph; commands name a target by its hat ("take blue air") and
a tree-sitter scope ("chuck funk"). This is the closest *interface* analog to
our selector glyphs: **cheap visual single-symbol handles bound to structural
targets, resolved by the interface rather than by re-typing the content**.
(Characterization from product docs/general knowledge — no paper; treat as a
design precedent, not an evaluated result.) The transferable design rule:
hats/glyphs are *stable across renders* for unchanged targets — churn in
glyph↔target binding is the primary usability failure their community
documents. Keep glyph assignment sticky per menu item across turns.

---

## Q4 — Plan-then-execute protocols and plan-tracking granularity

### Published results

- **Plan-and-Solve** (Wang et al., ACL 2023, arXiv:2305.04091): zero-shot
  "devise a plan, then carry it out" prompting beats zero-shot-CoT across
  math/commonsense benchmarks and matches 8-shot CoT on several — evidence
  that an *explicit plain-language planning phase* helps even with no
  machinery at all. (Background knowledge; the searches surfaced it via the
  paradigm surveys below.)
- **ReWOO** ([arXiv:2305.18323](https://arxiv.org/abs/2305.18323),
  [repo](https://github.com/billxbf/ReWOO)): plan once with *variable
  placeholders* for unseen results, execute tools separately, solve at the
  end — "**5× token efficiency and 4% accuracy improvement on HotpotQA**"
  (42.4% @ 2k tokens vs ReAct's 40.8% @ 10k tokens); "robustness under
  tool-failure scenarios."
- **The rigidity failure:** a 2026 benchmark survey of paradigms
  ([DeliveryBench, arXiv:2512.19234](https://arxiv.org/pdf/2512.19234) via
  search summary) finds that "under the plan-and-solve paradigm, due to the
  rigid 'plan-first, execute-later' logic and the absence of runtime dynamic
  adjustment mechanisms … this lack of feedback leads to catastrophic final
  results." Plan-first without re-planning is a trap on dynamic tasks.
- **Granularity:** the ReWOO-vs-plan-and-execute comparison
  ([wollenlabs survey](https://www.wollenlabs.com/blog-posts/navigating-modern-llm-agent-architectures-multi-agents-plan-and-execute-rewoo-tree-of-thoughts-and-react),
  [LangChain planning-agents post](https://www.langchain.com/blog/planning-agents))
  frames the axis: high-level steps + smart executor vs tool-precise steps.
  [GeoAgentBench (arXiv:2604.13888)](https://arxiv.org/pdf/2604.13888)-class
  work states the trade directly: "plans that are too broad lead to execution
  failures because the executor lacks sufficient guidance, while plans that
  are too specific lead to token bloat and increased latency." Also
  [Do Agents Need to Plan Step-by-Step? (arXiv:2605.08477)](https://arxiv.org/pdf/2605.08477)
  questions maximal-granularity planning for tool calling. No paper we found
  gives a clean ablation curve of *plan-tracking* granularity (open gap —
  our drives can measure it; win condition already defined in repo terms:
  continuity across restarts).

### Framework evidence (vendored)

- **hermes-agent `todo` tool**
  (`reference-code/hermes-agent/tools/todo_tool.py`) — the settled
  production shape:
  - Status enum: `{"pending", "in_progress", "completed", "cancelled"}`
    (`todo_tool.py:22`).
  - Items are `{id, content, status}`; `merge=True` updates by id (partial
    marking — exactly our glyph-marks-item-done write).
  - Render-into-context markers: `completed → "[x]"`, `in_progress → "[>]"`,
    `pending → "[ ]"` (`todo_tool.py:102-104`), and — load-bearing — "Only
    inject pending/in_progress items — completed/cancelled ones" are dropped
    from the context render (`todo_tool.py:108-112`). Done work leaves the
    prompt; open work re-renders every turn. This is our
    reactive-context/derive-don't-store principle independently reinvented.
  - `todo` is classified a **mutating** tool and counted by the tool-call
    loop-detection guardrail (`agent/tool_guardrails.py` — warn by default,
    opt-in hard stop): plan-thrash is a recognized failure mode worth
    detecting.
- **Voyager** (`reference-code/Voyager/voyager/prompts/curriculum.txt`):
  plan persistence as two flat lists fed back every turn — "Completed tasks
  so far: …" and "Failed tasks that are too hard: …" — plus the hard rule
  "Do not propose multiple tasks at the same time" (one `in_progress` item
  max) and verifiability as an admission criterion ("Tasks that require
  information beyond the player's status to verify should be avoided").
  Skills that pass the critic enter the skill library = completed plan items
  become durable capabilities.
- **letta/MemGPT** (`reference-code/letta/letta/`): plans persist as core
  memory the agent edits with explicit tools; the `request_heartbeat`
  control param (Q2) is what lets it chain plan-step → execution → plan
  update in one flow.

**Synthesis for our protocol:** the convergent design is (a) numbered plan
items in plain language, (b) a ≤4-value status enum, (c) ONE in-progress
item, (d) re-render only open items each turn, (e) allow re-planning
mid-execution (never lock the plan), (f) plan items must be *verifiable*
(Voyager's admission rule — pairs perfectly with our oracle: a plan item
should carry its check). Glyph-marking is then just a cheap write onto (b):
render each open item with its glyph; emitting ③ + a status glyph (or ⟹-style
convention) transacts the status change. Because plan status lives in the DB
and re-renders, the marking write is idempotent and restart-proof — the two
win conditions the repo already defines for drives.

---

## Q5 — Reading model uncertainty as interface behavior

- **Copilot-class confidence gating (deployed):** GitHub Copilot runs a
  logistic-classifier display/invocation filter with hard rules (no prompt
  <10 chars, no mid-line cursor invocation)
  ([via candede.com writeup](https://www.candede.com/articles/github-copilot-agent-token-optimization/));
  the academic version is
  [Smart Invocation of Automatic Code Completion (arXiv:2405.14753)](https://arxiv.org/pdf/2405.14753)
  — a transformer classifier deciding *when to show* a completion. Precedent:
  a model-side score gates whether the interface surfaces an affordance at
  all. Our analog: only render the ghost-form/menu when the posterior over
  candidates is peaked; suppress it when flat.
- **Speculative decoding acceptance as calibrated confidence:**
  [SpecKV (arXiv:2605.02888)](https://arxiv.org/html/2605.02888) — "summary
  statistics from draft-model logits like confidence, entropy, top-2 margin
  … a strong, consistent monotonic relationship where acceptance increases
  with confidence/margin and decreases with entropy/dispersion"; most
  informative features are *min* draft confidence (30.0%) and *max* draft
  entropy (24.1%) — worst-token-in-window statistics, not means.
  [Confidence-Modulated Speculative Decoding (arXiv:2508.15371)](https://arxiv.org/pdf/2508.15371),
  [DSpark (arXiv:2607.05147)](https://arxiv.org/html/2607.05147v1),
  [Entropy-Aware Speculative Decoding (arXiv:2512.23765)](https://www.arxiv.org/pdf/2512.23765)
  all use per-token entropy to decide *how far to run ahead* — structurally
  identical to "how much of the fill do we auto-accept before asking the
  oracle/user." Direct import: gate auto-accept on **min-confidence over the
  span**, not mean logprob — mean is diluted (next bullet).
- **Probability dilution in code:**
  [TableMind++ (arXiv:2603.07528)](https://arxiv.org/pdf/2603.07528) names
  the trap — "boilerplate syntax generates with near-perfect probability,
  inflating average scores and potentially masking low-confidence
  hallucinations in critical logical segments." For our candidate scoring
  (mean logprob at slot positions) this says: score only the *free* slot
  positions (we already do) and prefer min/quantile aggregation over mean
  for accept/flag decisions.
- **Uncertainty-triggered clarification:**
  [Uncertainty Decomposition for Clarification Seeking in LLM Agents (arXiv:2606.19559)](https://arxiv.org/html/2606.19559v1)
  — decompose action confidence from task-specification uncertainty; ask the
  human only when the *specification* is uncertain. Maps onto our two
  signals: flat posterior over menu candidates with high free-typing
  confidence = model knows what to do, menu is just wrong (don't ask);
  flat everything = ask/clarify. hermes-agent's `clarify(question, choices)`
  tool is the deployed sink for exactly that event.
- **Logprobs as product surface:** commodity now —
  [Together docs](https://docs.together.ai/docs/inference/chat/logprobs)
  ("measure confidence for each token, gate low-confidence outputs, or
  compare alternatives"); demand tracked in
  [pydantic-ai #1228](https://github.com/pydantic/pydantic-ai/issues/1228).
  Nobody we found closes the loop into *interface state* (menu
  show/hide/auto-accept) the way rounds S+4 do; nearest neighbors are the
  Copilot display filter and abstention knobs
  ([arXiv:2601.00138](https://arxiv.org/pdf/2601.00138)).

---

## Recommendations for the seon protocol (ranked)

### Adopt

1. **Keep selection strictly optional, forever** (highest confidence). Every
   documented catastrophe in Q2 — hallucinated args, constraint tax,
   premature triggering, 10–15% reasoning loss — is downstream of *forcing*
   the structured channel. The measured round-4 design (glyph optional, free
   Clojure always legal, zero modes) is already the mitigation the
   literature converges on. Treat any future "require a glyph here" proposal
   as a regression until measured.
2. **Calibrate and subtract the glyph position prior** before trusting
   posteriors (MCQ selection-bias literature, Q2 #4). One content-free-menu
   measurement per model gives p₀(①…⑩); decisions use the ratio/difference,
   and rank gaps smaller than the prior spread are noise. Add the
   **none-of-the-above threshold as a hard gate on acting** (not on
   emitting). Keep menus ≤5 items; keep glyph↔item binding sticky across
   re-renders (Cursorless lesson).
3. **Adopt Hazelnut's two-hole distinction + tylr's obligations in the
   canvas FSM** (Q3). States per span: `clamped/finished`, `empty-hole`,
   `non-empty-hole (failing, with structural obligation attached)`. Actions:
   move/construct/del/finish — our repair loop already implements del+
   construct; `finish` = lock-and-harvest. Obligations turn "parse failed"
   into "delimiter owed at position k," making boundary-artifact repair
   targeted. This grounds the FSM in a mechanized (Agda-proven) metatheory
   whose sanity properties (every state meaningful, actions total) are
   exactly our "cannot hallucinate by construction" claims.
4. **Plan protocol = the convergent checklist shape** (Q4): numbered
   plain-language items, status ∈ {pending, in-progress, done, cancelled},
   ONE in-progress item, re-render only open items each turn (drop done —
   hermes `todo_tool.py:108`), plan re-writable mid-run (the DeliveryBench
   rigidity failure), each item verifiable and ideally carrying its oracle
   check (Voyager admission rule). Glyphs are the *write path* onto this
   shape, not a new shape. Persist as `my.plan` datoms (already the repo
   pattern) so continuity-across-restart falls out.
5. **Uncertainty aggregation: min/worst-token over the span, never mean**
   (SpecKV feature importances + probability dilution). Use it for three
   interface behaviors, in order of safety: (a) rank menu candidates
   (already proven), (b) show/hide the ghost-form (Copilot display-filter
   precedent), (c) auto-accept a fill without oracle round-trip only above a
   high min-confidence bar — and even then the oracle still runs async.
6. **Keep the special-token ban at free positions** (already identified) and
   add glyph tokens to the ban list *outside menu-active renders* — a glyph
   emitted when no menu is displayed is a leaked control token.

### Design against (failure-mode register)

| Failure mode | Source | Mechanical counter |
|---|---|---|
| Selection/position bias in glyph posterior | 13–85% MCQ swings ([2308.11483](https://arxiv.org/abs/2308.11483)) | position-prior calibration; ≤5 items; permute on high stakes |
| Over-selection (menu invites wrong pick) | forced-choice arg hallucination ([931947](https://community.openai.com/t/forced-function-calling-making-up-values-for-required-parameters/931947)) | none-of-the-above gate on *acting*; menu always escapable |
| Constraint tax on reasoning | [2606.25605](https://arxiv.org/pdf/2606.25605), [2408.02442](https://arxiv.org/abs/2408.02442) | never grammar-mask code synthesis to the menu; classify-vs-reason split |
| Menu-only mode collapse | [2605.00435](https://arxiv.org/html/2605.00435) | monitor glyph-emission rate; drop menu after N consecutive selections |
| Plan rigidity (plan-first, no re-plan) | DeliveryBench catastrophic plan-and-solve | plan is DB state, always re-writable; executor feedback re-renders |
| Plan thrash | hermes loop guardrail precedent | count plan-mutation writes per window, derived warning section |
| Mean-logprob masking bad tokens | probability dilution ([2603.07528](https://arxiv.org/pdf/2603.07528)) | min/quantile aggregation at free positions only |
| Glyph↔item binding churn | Cursorless hat-stability lore | sticky glyph assignment across re-renders |
| Smart-mode-style per-edit authority inference | parinfer smart mode post-mortem | one invariant: clamps always win, free text always yields |
| Control-token leakage into content | round-4 observation; [2503.24191](https://arxiv.org/pdf/2503.24191) | logit ban at free positions / outside menu renders |

### Deliberately not adopted

- **Grammar-forcing the glyph channel** (xgrammar/outlines-style masks over
  the menu): the constraint-tax evidence says the cost lands exactly where
  we can least afford it (reasoning), and scoring-not-forcing already gets
  the benefit.
- **Toolformer-style learned trigger tokens** (finetuning the trigger in):
  unnecessary — round 4 shows the pretrained glyph affordance already fires;
  finetuning would also entangle us with one model.
- **Fine-grained tool-precise upfront plans (ReWOO-max granularity)** as the
  default: ReWOO's token win is real but its placeholder-plan rigidity is
  the documented failure on dynamic tasks; our loop is feedback-rich, so
  plan coarse, execute with the oracle.

## Open measurement questions (ours to run, three-surfaces rule)

1. Glyph position prior for DiffusionGemma (content-free menu, 10 glyphs,
   N seeds) — needed before any posterior-threshold ships.
2. Plan-tracking granularity ablation — no published curve exists; the
   existing drive win-conditions (resume-after-restart, recall-after-turns)
   are the right dependent variables. New task/scorer inside
   `src-inspect-ai`, per the testing directive.
3. Menu-present vs menu-absent A/B on task success (not just selection
   accuracy) — AgentOccam predicts a win, constraint-tax predicts a loss if
   the render crowds reasoning; both are plausible, measure it.
