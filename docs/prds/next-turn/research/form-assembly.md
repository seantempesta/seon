---
type: research
status: measured
lane: form-assembly
date: 2026-09-26
---

# Form assembly as typed choices

**The owner's question (2026-09-26).** Treat writing a Clojure form as a
sequence of typed choices. After `(` the agent picks a function (all functions
are indexed). Then it picks an arity. Then, for each argument, it picks from a
list of options whenever the admissible values are known. After each argument
it either adds another argument or closes the form with `)`. Nested forms
recurse. Has anyone already done most of the work of assembling the valid forms
an agent may want to run? How far can Seon take this?

**What already exists in Seon.** Seon already has the index this needs:
- program rows with arities and argument facts (`:seon.fn.arity/*`,
  `:seon.fn.argument/*`, `resources/seon/schemas/seon.fn.arity.edn:1-30`);
- Malli contracts declared as `{:malli/schema [:=> [:cat …] out]}` in each
  `defn`'s attribute map (2,121 occurrences under `src/`; e.g.
  `src/seon/call_preparation.clj:58`);
- one mechanism that already *fills* a typed hole: call preparation.

Call preparation joins each contract arity to the supplied-default rows **by
declared schema name** (`src/seon/call_preparation.clj:639-671`,
`arity-plan`). It fills a slot the caller omitted by calling that row's
supplier (`:862`, `supply`). There are four such rows:
`:seon.db/db`, `:seon.db/connection`, `:my.program/context` and
`:seon.agent/id` (`config/default.edn:537-555`). A supplied default is a typed
hole with exactly one candidate. Everything below generalizes that one idea.

This lane builds on `token-level.md` (§3: scope and contract determination at
the token level) and `prior-art.md` (§5: typed holes, MGD and type-constrained
decoding named). It does not repeat them.

## Bottom line

1. **Prior art has done the mechanics, not the ranking.** Each piece exists:
   - grammar-constrained decoding: PICARD, Synchromesh, llguidance/XGrammar;
   - type- and scope-constrained decoding: Mündler et al. 2025, MGD;
   - context from the typed hole: ChatLSP/Hazel;
   - type-directed enumeration: Hoogle+, Synquid;
   - LLM-guided enumeration: Li et al. 2024, HySynth.

   The measured lesson is the same everywhere. **Constraints remove invalid
   programs**: type constraining cuts compile errors 52–75% (Mündler), and
   T5-3B + PICARD goes from 12% to 2% unusable. **The model still ranks the
   valid choices.** No system replaces the model's choice of *which* valid
   function or value. Nobody has done this for a Lisp with runtime contracts
   (§A.6).
2. **Measured on 10,672 real `eval_clj` inputs (505k choice points, "holes"):**
   - About **35% of holes are closed and small** (≤ 32 candidates: arity, map
     key, enum/boolean, local binding, supplied default, threaded argument).
   - **31% are closed but large**: heads (10,311 candidates), var references,
     registry keywords, ids.
   - **19% are structural**: a nested form or collection whose own holes are
     counted separately.
   - **15% are free**: strings, numbers, quoted data and unregistered keywords.
     These are what the LLM must generate.
3. **Structural ranking alone is decent on small holes and weak on heads:**

   | hole | top-1 | top-5 | notes |
   |---|---|---|---|
   | arity | 84% | — | top-2 99% |
   | declared map key | 66% | 81% | median menu of 4; only 83% of written keys are in the declared set |
   | local-binding argument | 42% | 88% | ranked by recency; median 6 locals in scope |
   | head, all | 33% | 59% | top-10 70% |
   | head, program function | 31% | 47% | top-10 55% |

   The best head signal is the **parent slot**, meaning which function and
   argument position the form sits in: 28% top-1 on its own. Namespace
   context, call-graph neighbourhood and session recency add little.
4. **Whole calls.** Given the head, **35% of program calls are fully
   determined**: every argument is a closed choice ranked first. That drops to
   **15%** when the head must also be ranked first. With a lenient history
   match for free literals the figures are 53% and 23%.
5. **The biggest closable class is already built.** 27.5% of contracted
   positional arguments are **supplied-default slots** (connection/db). Outside
   agents fill them by hand because JVM mode has no custody; in SCI custody,
   call preparation fills them. The second-biggest class is a schema that is
   too weak: `seon.cluster.boot/connection` declares `:string`
   (`src/seon/cluster/boot.clj:597`), not the existing named schema
   `:seon.boot/cluster-name` (`src/seon/config.clj:822`). That leaves 2,611
   holes "free" that a roster lookup would close.
6. **What stays the LLM's job:**
   - choosing *which* function when the step is new (69% of program heads miss
     structural top-1);
   - every free literal (strings, numbers, Datalog query data);
   - nesting and composition;
   - when to stop.

   A structural assembler can offer menus, fill holes and check the form. It
   cannot choose the program.

---

## Part A: Prior art

In each entry: **C** is what the system constrains, **R** what it ranks, **M**
the measured effect, and **T** what transfers to Seon. Numbers were read from
the papers' own tables (PDF text in `tmp/next-turn-form-assembly/papers/*.txt`)
unless marked otherwise.

### A.1 Syntactic / AST neural code generation (2017–2020)

| system | C | R | M | T |
|---|---|---|---|---|
| **Yin & Neubig 2017**, syntactic neural model ([arXiv 1704.01696](https://arxiv.org/abs/1704.01696)) | Output is a sequence of grammar actions (ApplyRule / GenToken) over the Python ASDL, so every output parses | A neural net scores each action given the parent node ("parent feeding") | HS 16.2% exact / BLEU 75.8 vs LPN 4.5 / 65.6. Django 71.6% vs 62.3%. **Removing token copying drops Django from 71.6% to 32.3%** (ablation table) | The action sequence is the owner's "sequence of typed choices". Its most valuable part was *copying terminals from context*, which matches our finding that 52% of leaf arguments appear in the previous 3 evals. Not transferable: they trained a model per dataset. |
| **TRANX** (Yin & Neubig 2018, [arXiv 1810.02720](https://arxiv.org/abs/1810.02720)) | The same transition system, generic over any ASDL grammar | Neural action scoring | Django 73.7%; GEO/ATIS competitive with ASN (85.7/85.3) | The grammar is data. For Seon the "grammar" per head is the Malli contract, which is also data. |
| **TreeGen** (Sun et al. AAAI 2020, [arXiv 1911.09983](https://arxiv.org/abs/1911.09983)) | Grammar rules | Tree-structured Transformer | HS StrAcc 25.8–31.8% vs YN17 16.2%. A plain Transformer baseline gets 10.6% | Structure helped *small, from-scratch* models. It predates LLMs; general LLMs now learn syntax implicitly, so the value moved to the *semantic* constraints below. |

**Reading.** AST decoders proved that making every choice point a typed choice
guarantees well-formed output. They also showed the *ranking* of those choices
still needs a learned scorer and context copying. They did not survive
pretrained LLMs as generators. Their decomposition survives inside constrained
decoding.

### A.2 Grammar-constrained decoding

| system | C | R | M | T |
|---|---|---|---|---|
| **PICARD** (Scholak et al. EMNLP 2021, [arXiv 2109.05093](https://arxiv.org/abs/2109.05093)) | Incremental SQL parse with guards: tables and columns must exist; aliases must resolve. Checks the top-k tokens at each beam step | The LM (T5); beam search | Spider dev EM/EX: T5-Base 57.2/57.9 → **65.8/68.4**; T5-3B 71.5/74.4 → **75.5/79.3**. Unusable SQL 12% → 2%. Decoding 2.5 → 3.1 s/sample (+24%) on A100 | Closest in spirit to the owner's idea: incremental parse plus *scope guards* (identifiers must exist). Gains grow with beam size, because rejecting invalid hypotheses needs alternatives. Not transferable: it needs logit/beam access, so it is local-only for Seon (token-level §1). |
| **Synchromesh** (Poesia et al. ICLR 2022, [arXiv 2201.11227](https://arxiv.org/abs/2201.11227)) | "Constrained Semantic Decoding": syntax, scope, typing and domain rules via a completion engine. Works with GPT-3/Codex through the API | The LM, plus retrieval of examples by *target-program* similarity (TST) | Complementary gains on SQL, Vega-Lite and SMCalFlow; prevents runtime errors (abstract; table numbers not extracted) | Its completion engine asks "which tokens can legally come next" and is language-agnostic. For Seon the answer comes from the reader plus the contract. Its TST is exactly "retrieve past forms like the one being written". |
| **llguidance / XGrammar / Outlines** | Any CFG/regex/JSON schema as a token mask | The LM | Mask cost ~50 µs/token (llguidance) and < 40 µs (XGrammar); negligible against a ~9 ms decode step (token-level §2) | The infrastructure is solved. An s-expression CFG is about 20 lines. The **context-sensitive** part (heads in scope, keys per contract) must be a logits processor fed from Seon's rows. |
| **DOMINO** (Beurer-Kellner et al. ICML 2024, [arXiv 2403.06988](https://arxiv.org/abs/2403.06988)) | Subword-aligned ("minimally invasive") masks | The LM | Naive masks distort tokenization and hurt accuracy. Aligned masks plus speculation reach "virtually no overhead" and up to ~2× speed-up | A symbol-level menu must be enforced at the *token* level without forcing unnatural tokenizations. Use a trie over candidate strings, not whole-symbol tokens (needle's `constrained.py` trie). |

### A.3 Type- and scope-constrained decoding

| system | C | R | M | T |
|---|---|---|---|---|
| **Mündler et al., Type-Constrained Code Generation** (PLDI 2025, [arXiv 2504.09246](https://arxiv.org/abs/2504.09246)) | Prefix automata admit only prefixes that can still be completed to a well-typed TypeScript program ("search over inhabitable types") | The LM | On HumanEval/MBPP synthesis, compile errors fall **75.3% / 52.1%** (syntax-only constraining: 9.0% / 4.9%). pass@1 rises 3.5% (synthesis), 5.0% (translation) and **37% relative (repair)**; Gemma-2-2B repair 11.6 → 20.9. **Runtime +20–60% per sample.** 94% of compile errors are type errors, only 6% syntax | The strongest evidence that *semantic* constraints are where the value is: syntax alone did almost nothing. Seon's analogue is Malli contracts plus scope. Not transferable: Malli is a runtime predicate language (`[:fn pred]`), so "inhabitable" is undecidable for opaque predicates. Only enums, keys, arity and named schemas with enumerable members give prefix-checkable constraints (measured §B.3: rare). |
| **Monitor-Guided Decoding** (Agrawal et al. NeurIPS 2023, [arXiv 2306.10763](https://arxiv.org/abs/2306.10763)) | At trigger points (after `.`) a language-server monitor masks identifiers to those type-consistent with the receiver; also argument counts and API protocols | The LM | Compilation rate **+21.8–24.7% relative** for every base model. Next-identifier match +7.1–8.9%. SantaCoder-1.1B + MGD beats text-davinci-003 on both | The closest match to "head hole = menu from the index". The trigger-point design (constrain only at a few positions) fits Seon: constrain at head, key and arity holes and leave the rest free. |

### A.4 Typed holes and LLMs

**ChatLSP / Hazel** (Blinn, Li, Kim, Omar, OOPSLA 2024,
[arXiv 2409.00921](https://arxiv.org/abs/2409.00921)).
- **C:** nothing is constrained. The *context* at a typed hole is its expected
  type, the relevant type definitions and function headers whose types relate
  to the hole, plus up to 2 static-error correction rounds.
- **M** (GPT-4 on MVUBench, 20 trials, figure-level):
  - type definitions are "absolutely necessary";
  - headers on top of types multiply test performance **~3×** in Hazel and
    ~1.5× in TypeScript;
  - error rounds give ~2× in Hazel and ~1.2× in TypeScript;
  - StarCoder2-15B improves "by an order of magnitude" with types, and +50%
    with headers.
- **T:** the hole's expected *schema* plus the functions that produce that
  schema is exactly what a Seon typed hole should render. Seon has both: the
  argument's named schema, and functions whose output schema matches it
  (`::arity-output` in `call_preparation.clj:175-185`).
- **Caveat from the paper:** type-relevant but irrelevant headers degraded two
  tasks. Relevance by type alone over-retrieves.

### A.5 Type-directed synthesis and enumeration

| system | C | R | M | T |
|---|---|---|---|---|
| **Hoogle+ / TYGAR** (Guo et al. POPL 2020, [arXiv 1911.04091](https://arxiv.org/abs/1911.04091)) | Well-typed straight-line compositions of 291 Haskell library components, found by reachability over abstract types | Enumeration order plus a test-based filter | An "interesting" solution in the top 5 for **33/44** queries; median 1.4 s to the first solution | Seon's contracts are an index of the same kind (input schema name → output schema name). "Which call chain produces a `:seon.db/database-value` from what I hold?" is a Hoogle+ query. It works for *named* schemas only. |
| **Synquid / Myth / SyGuS** | Refinement types, examples, or a grammar plus a logical spec | Enumeration by size/cost | Complete for small programs; exponential in program size | Too heavy per REPL form. The useful residue is "enumerate small closed holes exhaustively". |
| **LLM + enumerator** (Li, Parsert, Polgreen CAV 2024, [arXiv 2403.03997](https://arxiv.org/abs/2403.03997)) | SyGuS grammar | A pCFG learned from LLM samples guides A*/enumeration; the LLM is re-prompted with progress | On 609 SyGuS problems: LLM alone 49.8%, enumerator 31.0%, cvc5 68.1%, **LLM ∪ A\*-pCFG 80.1%** | **Hybrid beats both parts.** The LLM's intuition lives in weights over choices, and search completes the details. |
| **HySynth** (Barke et al. NeurIPS 2024, [arXiv 2405.15880](https://arxiv.org/abs/2405.15880)) | A DSL grammar; a PCFG fitted to 10–100 LLM samples (invalid ones included) | Bottom-up search weighted by that PCFG | **58%** of tasks vs 40% unguided and 6% LLM alone | The same shape as a Seon hybrid: LLM samples → per-slot weights → closed holes filled by search. Needs a checker to accept candidates; Seon's is contracts plus tests. |

### A.6 Lisp/Clojure-specific

- **nREPL completion** (vendored compliment-lite,
  `reference-code/nrepl/src/clojure/nrepl/util/completion.clj:672-682`)
  completes by prefix over namespace vars, classes, keywords and special forms.
  It **sorts alphabetically** (`sort-by :candidate`), with no position or type
  awareness.
- **clojure-lsp** sorts locals before vars and completes unrequired namespaces
  with an alias insert ([changelog](https://clojure-lsp.io/CHANGELOG/)).
  **[C]** no measured accuracy is published.
- **Malli → clj-kondo** already exports function contracts as static
  `:type-mismatch` configs: per-arity `:args`/`:ret` types
  (`reference-code/malli/src/malli/clj_kondo.cljc:198-227`), and enums map to
  a kondo type (`:85`). This is a checking seam, not a proposing one.
- **Needle autosuggest (Seon, archived)** had a trie-constrained decoder
  (symbols → program vars, keywords → registry) ruled "load-bearing". Its
  measured ceiling was context coverage, not decoding
  (`git show 607147a62^:docs/prds/repl-autosuggest/design.md`; token-level §2).
- **Lisp in LLM synthesis:** Li et al. 2024 report that Lisp-syntax responses
  solved **6% more** of 50 SyGuS benchmarks than Python (arXiv 2403.03997 §4).
  One small data point in favour of s-expressions as the generated surface.
- **Found nothing** that assembles Clojure forms from runtime contracts hole
  by hole, measured or not. Paredit and Parinfer guarantee balance only.
- **Classic API-call completion** is the closest measured analogue for head
  ranking: Pythia (Svyatkovskiy et al. KDD 2019,
  [arXiv 1912.00742](https://arxiv.org/abs/1912.00742)) reaches **92% top-5**
  for method completion *after a receiver `.`*. That position narrows
  candidates to one type's members. Clojure's head position has no receiver,
  which is why our head numbers are much lower (§B.3).

### A.7 What the literature says Seon can and cannot get

- **Can:** eliminate unknown-symbol, arity and declared-key errors; offer
  exact menus at trigger points; render hole-typed context (schema plus
  producer functions); let a small model fill closed holes.
- **Cannot:** a menu does not choose. Every system above leaves the choice
  among valid candidates to a learned model. The best hybrids improve on the
  LLM alone (49.8% → 80.1%) because search fills what the LLM sketches, not
  because structure replaces the LLM.
- **Beware:** constraints cost runtime (+20–60% for type constraints, +24% for
  PICARD). Naive token masks distort outputs (DOMINO). Type-relevant retrieval
  over-retrieves (ChatLSP).

---

## Part B: Measured on real data

### B.1 Method

- **Corpus:** the token-level lane's
  `tmp/next-turn-token-level/evals.jsonl`. It holds 13,758 `eval_clj` calls
  from 639 Claude Code transcripts (main sessions and subagents), 2026-07-31 →
  09-26.
- **Measured set:** first occurrences since 2026-09-12, **10,672 evals**.
  Earlier evals only train the priors.
- **Caveat:** these are outside agents, mostly in JVM mode. Product agents'
  `:seon.cluster.eval/source` rows are unmeasured.
- **Reader:** the positional Python reader `cljreader.py` (token-level lane).
  It was checked against **edamame** inside babashka (not a JVM):
  - `reader_check.clj` parses all 13,758 inputs in 0.96 s;
  - `reader_compare.py` agrees on 13,712 inputs (identical top-level form
    counts) and both reject 43;
  - the Python reader accepts 3 that edamame rejects;
  - **disagreement 0.02%, parse failure 0.33%.**
- **Index:** static, from `src/`, `test/`, `reference-code/{malli,datahike,sci,core.async,konserve}`,
  the clojure.core publics and `resources/seon/schemas/*.edn`.
  - 10,286 vars; 2,080 program vars carry `:malli/schema` in the defn
    attribute map.
  - 3,498 registry keys.
  - Contracts are parsed as `:=>` with `:cat`/`:catn`, and `:function`.
  - Registry keywords are followed to structural schemas; `::kw` is resolved
    per namespace.
  - Static reference graph: 9,250 defn→var edges from `src/` (≈ `:seon.fn/calls`).
- **Walk** (`tmp/next-turn-form-assembly/holes.py`, 53 s, NumPy, no models).
  Every unquoted list form with a symbol head yields:
  - a **head** hole;
  - an **arity** hole (known-arity, non-macro callees);
  - one **arg** hole per argument, classified by the callee's contract at that
    position when the call count matches a contract arity;
  - **key/value** holes for literal maps passed where the contract declares
    keys (recursing into nested declared maps);
  - `->`/`->>` threaded slots, and omitted supplied slots (derived like
    `arity-plan`).

  Binders and `fn` bodies introduce locals in binding order. Session state
  carries `require` aliases, session `def`s and the previous 3 evals with their
  result heads.
- **Oracle-free rankers:** every prior is updated only *after* the hole is
  scored. Global priors come from strictly earlier evals; session priors from
  earlier evals in the same transcript.
- **Outputs:** `results.json`, `holes.pkl` (every hole),
  `run.log`. Rerun:
  `src-needle/.venv/bin/python tmp/next-turn-form-assembly/holes.py`.

Totals: 169,450 head, 101,179 arity, 224,477 arg, 5,092 key and 5,092 value
holes. There are also 116,269 element holes inside literal collections, kept
separate. That is a median of **35 holes per eval** (p90 103; excluding
elements) and 12 heads per eval (p90 34). 6,489 evals call at least one
program function, with a median of 3 program heads each.

### B.2 Candidate-set size by hole kind

| hole kind | count | candidate set | share of written fillers the set covers |
|---|---|---|---|
| **head** | 169,450 | **10,311** indexed callables. 15.5% of heads are locals, interop or unresolved and not indexable | 84.5% indexable |
| **arity** | 101,179 | 1: 47.7%; 2: 27.9%; 3: 14.2%; 4+: 10.2%. 28.9% of callees are variadic, so ")" stays a choice after the fixed part | 100% (known-arity callees) |
| **arg, enum schema** | 123 (+295 map values) | median 2 (values: 5) | 99% / 90% |
| **arg, boolean** | 25 (+222 values) | 2 | 100% |
| **arg/value, declared map** | 3,804 args carrying 5,092 key holes | median **4** keys available per key hole (p90 13); median 5 declared per map | **83.1%** of written keys are declared (§token-level: 83.9%) |
| **arg, supplied-default slot** (conn/db/context/agent-id) | **9,199 written** + 824 as map values; **55 omitted** | 1 (the supplier) | 100% |
| **arg, ref/id/entity** (`:seon.db/ref`, `…/id`, `:qualified-symbol`, `:uuid`) | 1,647 (+531 values) | database-sized (lookup refs are 867 of them) | — |
| **arg, keyword-typed** (`:keyword`, `:qualified-keyword`) | 628 (+218) | registry 3,498 (if registry-bound) | 26% of fillers are registry keys |
| **arg, function-valued** (`:=>`, `ifn?`) | 6 | all fns | — |
| **arg, free literal** (`:string`, `:int` …) | 5,918 (+694) | unbounded | — |
| **arg, collection / open map / any / opaque `[:fn pred]`** | 5,444 / 2,116 / 315 / 1,553 | unbounded | — |
| **arg to an uncontracted callee** (core, library, macros) | **189,950 (84.6% of args)** | unknown from schema | — |
| **contracted args by class** (33,437) | supplied **27.5%**, free literal 17.7%, collection 16.3%, declared map 11.4%, threaded 7.8%, open map 6.3%, ref/id 4.9%, opaque 4.6%, keyword 1.9%, any 0.9%, **enum 0.4%**, boolean 0.1% | | |

**Fillers of all arg holes:**

| filler | share | notes |
|---|---|---|
| nested call | 29.8% | |
| local binding | 25.6% | median 6 locals in scope, p90 12 |
| string | 5.8% | |
| quoted data | 4.7% | mostly Datalog |
| number | 4.7% | |
| other symbol | 3.8% | |
| vector literal | 3.7% | |
| map literal | 3.2% | |
| unregistered keyword | 3.1% | |
| fn literal | 2.6% | |
| var | 2.2% | |
| registry keyword | 1.7% | |
| session `def` | 1.3% | |

**Closed vs free**, over head/arity/arg/key/value holes:

| class | all holes | leaf holes only | args only |
|---|---|---|---|
| closed-small (≤ 32 candidates) | 34.9% | **43.2%** | 30.5% |
| closed-large | 31.1% | 38.5% | 4.6% |
| structural (nested form or collection) | 19.2% | — | 42.4% |
| **free** (LLM must generate) | 14.8% | **18.3%** | 22.5% |

"Closed-small" includes locals and supplied slots. **Schema-enumerated values
(enum/boolean/const) are under 2% of contracted arguments.** Seon's contracts
close *structure* (keys, arity, supplied slots), not *values*.

### B.3 Oracle-free structural ranking

**Head holes.** The candidate set is all 10,311 indexed callables. Ties count
half.

| ranker (single signal, backoff to global frequency) | all heads (143,174) top-1 / 5 / 10 | program heads (21,725) top-1 / 5 / 10 |
|---|---|---|
| global frequency | 7.8 / 20.5 / 29.8 | 0.0 / 5.3 / 26.7 (core forms always outrank) |
| namespace-in-context (session aliases + namespaces of the last 3 evals' heads + this eval so far) | 2.9 / 11.0 / 16.0 | 8.1 / 33.5 / 46.1 |
| call-graph neighbourhood of the previous eval's heads (±1 hop over static refs) | 7.4 / 23.9 / 36.0 | 0.4 / 20.4 / 34.1 |
| session MRU | 4.5 / 19.5 / 33.7 | 4.5 / 15.2 / 29.7 |
| session frequency | 7.9 / 25.6 / 39.3 | 2.1 / 21.6 / 36.4 |
| **parent slot** (which fn/position or binder the form sits in) | **30.4 / 56.2 / 66.6** | **28.4 / 43.6 / 50.1** |
| head bigram (previous head written) | 21.9 / 46.4 / 58.1 | 24.7 / 42.9 / 49.4 |
| **combined** (log-count sum of the above, weights fixed by hand once) | **32.8 / 59.0 / 69.7** | **30.9 / 47.0 / 54.5** |
| library heads, combined | 7.4 / 25.5 / 36.6 | |

The combined weights were chosen after looking at the single-signal rows. They
were not fitted, but they are not blind either, so read them as ≈ upper-bound
structural.

- Namespace context and the call graph help program heads at top-10 but not
  at top-1.
- The predictability lane's file-level ensemble reached 37/64/74. Heads are
  harder because each eval writes ~12 of them, most of them core forms.

**Arity.** Ranked by the callee's past argument counts, then shortest first:
top-1 84.0%, top-2 98.8%. For multi-arity callees alone: top-1 70.5%.

**Closed argument holes** (history at that `(fn, position)`, then declared
order):

| hole | top-1 | top-5 |
|---|---|---|
| enum args | 76% | 99% |
| enum map values | 72% | 90% |
| boolean | 96–98% | — |
| declared keys (menu of available keys, ranked by past use at that fn/path, then declaration order) | 66.4% | 81.2% (ceiling 83.1%) |
| local-binding args (most recently bound first) | 42.3% | 87.7% (top-10 97.9%) |
| session `def` args | 35% | 69% |

**Does the filler come from the previous 1–3 forms?** This is measured over
127,880 leaf argument/value holes (no nested forms or collections), matching
atoms or substrings of the previous evals' source and result head.

| measure | rate |
|---|---|
| filler appears in the previous eval | **38.4%** |
| filler appears in the previous 3 evals | **51.5%** |
| filler appears in the previous 3 evals, *or* is a local/session binding | **73.8%** |
| exact same text was the most frequent filler at this `(fn, position)` before | 29.9% |
| any menu (slot history, locals, enum) has it in top-5 | 70.1% |

Result heads are clipped at 300 characters for successful evals, so these
are floors. By filler:
- strings 65%;
- session `def`s **85%**;
- locals 52%;
- numbers 46%;
- nested calls 28% (whole-form repeats).

### B.4 Whole forms determined given the head

A call is **strictly determined** when:
- its arity is ranked first;
- every argument is a closed choice ranked first: an enum/boolean/const value,
  a local or session binding by recency, a supplied slot, a threaded slot, or
  a declared-key map whose keys and values are all ranked first;
- every nested call is itself determined *and* its head is ranked first by the
  combined ranker.

A call is **leniently determined** when, in addition, any argument may be the
top-1 filler at that slot from history (e.g. the same `"default"` again).

| calls | n | strict | strict + own head top-1 | lenient | lenient + head top-1 | zero-arg |
|---|---|---|---|---|---|---|
| all indexed calls | 119,277 | 12.4% | 4.6% | 20.1% | 7.1% | 1.7% |
| **program calls** | 21,725 | **34.9%** | **15.3%** | **53.2%** | **23.4%** | 4.7% |
| program calls with ≥ 1 argument | 20,705 | 32.8% | 15.8% | 52.0% | 24.3% | — |

**Reading.** Once the agent has chosen a program function, a third of its
calls could be completed by the system with no generation. Half could be, if
"same literal as last time" is accepted. Most of the strict share is
supplied-default slots plus recency-ranked locals. Choosing the head is the
bottleneck: program head top-1 is 31%.

### B.5 Findings the index exposed (defects, not edited here)

- **Weak schemas leave closable holes free.** `seon.cluster.boot/connection`
  takes `:string` (`src/seon/cluster/boot.clj:597`), but `:seon.boot/cluster-name`
  exists (`src/seon/config.clj:822`). There were 2,611 `(seon.cluster.boot/connection "default")`
  arguments. The same pattern applies to `seon.config/effective` (203 string
  arguments) and `seon.cluster/refresh-source!` (104).
- **Unions that carry errors hide the real choice.** `[:or :seon.db/connection :seon.error/value]`
  (`src/seon/db.clj:2248`) classifies as a map unless errors are stripped.
  A hole ranker must treat declared error alternatives as pass-through, not as
  options.

---

## The CLM experiment, specified for the orchestrator to run

Nothing was run on the GPU. The question is whether CLM-8B, used as a scorer
over hole candidates, beats the structural rankers above, and at what cost.

- **Sample.** 500 evals since 2026-09-20 with ≥ 1 program head (from 6,489),
  stratified by transcript. Holes come from `holes.pkl` (`ev` indexes the
  measured order).
- **State text (one per hole):**

  ```
  <previous 3 eval sources, each followed by its result head (≤300 chars)>
  ;; now writing:
  <current eval source up to the hole's start offset>
  ```

  Keep the last 160–1,024 tokens, since the encoder keeps the tail
  (`src-clm/src/seon_clm/encoder.py`, `ids`).
- **Candidate texts per hole kind:**
  - **head:** a card `ns/name [arglists] — first docstring line — :malli/schema form (≤ 60 tokens)`.
    Pool A is all 10,311, with vectors cached once. Pool B is the structural
    combined top-50 ∪ {truth}, to measure reranking.
  - **declared key:** `key — value schema form`. Pool: available declared
    keys (median 4).
  - **enum/boolean:** the literal value. Pool: the schema's children.
  - **local argument:** `name = <init form text>`. Pool: locals in scope
    (median 6).
  - **arity:** `ns/name with N args: [params]`.
- **Metrics:** top-1/5/10 per hole kind for three arms: structural combined,
  CLM, and rank-sum(structural, CLM). **Pre-registered bars:**
  - program-head top-5 ≥ 60% (structural 47%);
  - key top-1 ≥ 80% (structural 66%);
  - local top-1 ≥ 60% (structural 42%).

  The predictability lane found zero-shot CLM losing badly at next-file
  (8% vs 36% top-1), so the head bar is expected to fail. The value may be
  only in first-touch heads.
- **Cost, without KV reuse** (estimate from the lane's measured 70 ms per
  160-token state and 520 ms per 2k-token state):
  - 500 evals × ~48 holes = 24k state encodes;
  - **28 min at 160 tokens, ~3.5 h at 2k tokens**;
  - card vectors for 10,311 heads are a one-off of a few minutes (estimate;
    `scripts/bench.py` measures the card rate);
  - scoring is 1.6 ms per 100k cached vectors.
- **Cost, with prefix KV reuse.** Consecutive holes of one eval share the
  whole prefix: the partial form only grows by appending. The average append
  is **~3.8 BPE tokens per hole** (1.91M tokens ÷ 505k holes). CLM pools the
  last token of a causal model, so the state after appending is *exactly* the
  last hidden state of a cached forward: no approximation.
  - `encoder.py:54-59` calls `self.body(x)` without a cache today.
  - mlx-lm's `Qwen3Model.__call__(inputs, cache=…)`
    (`src-clm/.venv/.../mlx_lm/models/qwen3.py:142-147`) and `make_prompt_cache`
    (`…/models/cache.py:15`) support it without a fork.
  - Per hole the cost becomes one ~4-token forward over the cache, about a
    decode step: **estimate 20–40 ms** for 8B bf16 on M5 Max. One full prefix
    encode per eval is 70–520 ms.
  - Per eval (35 holes median): **~1.0–1.9 s instead of 2.5–18 s**. For the
    whole experiment: **~15–25 min**.
- **Code:** ~120 lines of Python in `tmp/` (a cached-prefix `embed_append`
  plus a loop over `holes.pkl`). No production change.

---

## Part C: Design sketches for Seon

All costs are estimates. Line counts are Clojure unless noted, and each
design names what it deletes, because designs over ~100 added lines need
matching deletions or a justification (CLAUDE.md, "Design the smallest
composition").

### C.1 System-proposed next forms, shown to the agent

- **What.** At turn start, derive up to 3 proposed forms from:
  - the agent's recent evaluations (`:seon.cluster.eval/source`);
  - the parent-slot and bigram head statistics;
  - hole fillers ranked as in §B.3.

  Render them as a REPL block of runnable forms, not prose (the AI render
  contract). Proposals reuse the one reader and the rows. Statistics are a
  memoized fold over eval rows keyed by commit, not an atom.
- **Measured ceiling.** Program head top-5 47% × fully-determined-given-head
  15–23% gives roughly **7–11%** of program calls exactly right in a 5-form
  proposal (estimate; the product is not independent). Proposals mostly help as
  *templates*: head and keys right, literals to edit.
- **Cost.** 3–4 days; ~120 lines (a slot/bigram fold, a filler ranker, a
  render). Latency: count folds over the session's evals, < 10 ms warm
  (estimate). Tokens: ~50–150 per turn.
- **Deletes.** Nothing obvious. It competes for the same context budget as
  the rendered walk, so it must displace something the synthesis ranks lower.
- **Falsification.** Replay product-agent eval rows. If exact match of any
  proposed form against the next eval's top-level forms is < 5%, or a
  30-turn A/B shows no fewer turns or tokens, drop it.

### C.2 Typed-hole protocol: the agent writes holes; the system fills or returns options

- **What.** The agent writes an ordinary form with a hole marker, for example
  `(seon.config/effective _ _)` or `(seon.db/transact! [{:seon.fn/sym _ …}])`.
  It calls one program function, for example `(my.program/options '(…))`,
  whose value is data:

  ```clojure
  [{:path [1] :schema :seon.boot/cluster-name
    :candidates ["default" "scratch-7"] :source :roster}
   {:path [0] :schema :seon.db/database-value
    :filled :supplied :supplier seon.db/supplied-database-value}
   {:path [2 :seon.fn/sym] :candidates […] :source :history}]
  ```

  Holes whose declared schema name has a supplier row are **filled**, which is
  exactly `arity-plan` + `supply` (`call_preparation.clj:639-671`, `:862`). A
  new row kind would add *enumerators*:

  | hole's schema | enumerator | source |
  |---|---|---|
  | enum | `m/children` | Malli |
  | map keys | `m/children` of `m/deref-all` | `call_preparation.clj:154-167` already extracts them |
  | identity attribute | Datalog over that attribute | Datahike |
  | local | the SCI context's bindings | SCI |
  | function-valued | contracts whose output schema matches | `::arity-output` |

  An enumerator is declared as one more row: schema name → enumerator
  function, the same "adding one row plus one ordinary contracted function"
  pattern (`call_preparation.clj:18-19`). A hole with no enumerator returns
  its schema form and producer functions (the ChatLSP recipe).
- **Measured value.**
  - Among holes the agent writes, 43% of leaf holes are closed-small and 38%
    closed-large.
  - Key menus are top-5 81% and locals top-5 88%.
  - The protocol's largest *immediate* win needs no new code: supplied slots
    are 27.5% of contracted arguments. Product agents in SCI custody already
    omit them; outside agents cannot, because JVM mode has no custody.
  - The second win is schema tightening (§B.5), a data fix.
- **Cost.**
  - Enumerator rows plus `options`: 3–4 days, ~100 lines.
  - Schema tightening for the named cases: 0.5 day, a few lines each.
  - Latency: one Datalog read per ref hole; < 5 ms warm for small attributes
    (estimate).
- **Deletes.** No duplicate path exists today. The justification is
  accretion: it generalizes the supplied-default join rather than adding a
  second mechanism. If it cannot share `arity-plan`'s join, it is the wrong
  design.
- **Falsification.**
  - Offline: for each written hole in the corpus, would `options` have listed
    the true filler in the top 5? Needs ≥ 70% on the holes it claims to
    close; §B.3 already gives 81% on keys and 88% on locals.
  - Online: if product agents call `options` in < 1% of turns over 200 turns,
    agents don't want menus. Keep only the silent fill (already built).

### C.3 Constrained local decoding: every structural choice ranked, only literals generated

- **What.** A local model (Qwen2.5-Coder-1.5B or the cached A3B) drafts forms
  through an `mlx_lm` `logits_processors` hook (`generate.py:307-313`,
  token-level §2). The hook is a small state machine over the reader's
  partial parse:
  - at a head position it allows only a trie of in-scope callables;
  - at an arity boundary it forces ")" when the arity is fixed and full;
  - at a declared-map key it allows only the remaining declared keys;
  - at an enum it allows its values;
  - locals are allowed everywhere.

  Literals are free. Candidate *ranking* inside a menu is the model's own
  logits, optionally mixed with the §B.3 structural prior (a logit bias over
  the trie's first tokens).
- **Measured value.**
  - The constraint touches the 66% of holes that are closed. It can remove the
    statically catchable errors (3.9% of evals; token-level §3) and the
    qwen-lora parse collapse (.895 → .593).
  - It **does not choose**. Structural top-1 is 31% for program heads, so the
    model's logits carry the choice. This matches MGD/Mündler: fewer invalid
    programs, modest correctness gains.
- **Cost.** 5–8 days; ~250 lines of Python (trie, state machine, scope
  export) plus a scope/contract export function (~40 lines Clojure). Runtime:
  mask ~50 µs/token plus a trie lookup, so +5–25% per sample (estimate; the
  literature reports +20–60% for type constraints).
- **Deletes.** Nothing in Seon. It is only worth building if a local agent
  tier is a product decision (token-level idea 3).
- **Falsification.** Replay 500 evals as "complete the eval from its first
  head". Compare syntax, unknown-symbol and arity errors, and exact match of
  heads and keys, constrained vs unconstrained on the same model. If errors do
  not fall by at least half, or exact match does not rise, stop.

### C.4 Human autocomplete in the web REPL

- **What.** The web REPL input (`src/seon/render/web.clj:299` currently sets
  `autocomplete "off"`) calls the same `options` function as C.2 on each
  keystroke boundary (symbol end, space, `{`). It renders the menu as a
  Datastar fragment:
  - a head menu ranked by parent slot and bigram;
  - an arity/arglist hint;
  - key menus from the contract;
  - local names from the agent's SCI context.

  There is one options function for humans and agents. That is the one-path
  law ("two consumers on one pipeline").
- **Archived precedent.** The repl-autosuggest PRD
  (`git show 607147a62^:docs/prds/repl-autosuggest/design.md`) aimed a 26M
  model at whole-form suggestions and was halted for data/instrument
  correctness, not speed. This design needs no model.
- **Measured value** (estimate for the human case: same menus, different
  author):
  - arity hint right 84%;
  - key menu top-5 81%;
  - locals top-5 88%;
  - head top-10 70% (all) and 55% (program).
- **Cost.** 3–5 days: ~80 lines of Clojure over C.2 plus ~60 lines of
  JS/Datastar. Latency: menus are count lookups plus one Datalog read, < 20 ms
  server-side (estimate), within keystroke budget.
- **Deletes.** Nothing. It depends on C.2; without C.2 it is a second
  completion engine and should not be built.
- **Falsification.** Log menu shown vs accepted for 2 weeks of owner use. If
  acceptance is < 20% of shown menus, remove it.

### What must remain the LLM's job

- **Choosing the function** for a new step. 69% of program heads miss
  structural top-1, and 45% miss top-10. Only a model reading the task, or
  CLM with task text, can close this.
- **Free literals:** strings, numbers and quoted Datalog, 18% of leaf holes.
  About half repeat something from the last 3 evals, and those are copyable,
  not choosable.
- **Composition:** which nested form goes where (19% structural holes), and
  queries.
- **Intent and stopping.** A menu never says what to do next.

The measured split suggests a division of labour:
- the **system** fills supplied slots, forces arity closes, offers key, enum
  and local menus, and checks every form;
- the **model** chooses heads and writes literals and queries.

That is the MGD/PICARD split, applied at Seon's contract boundary.

## Sources

- Yin & Neubig 2017, [arXiv 1704.01696](https://arxiv.org/abs/1704.01696)
- TRANX, [arXiv 1810.02720](https://arxiv.org/abs/1810.02720)
- TreeGen, [arXiv 1911.09983](https://arxiv.org/abs/1911.09983)
- PICARD, [arXiv 2109.05093](https://arxiv.org/abs/2109.05093)
- Synchromesh, [arXiv 2201.11227](https://arxiv.org/abs/2201.11227)
- DOMINO, [arXiv 2403.06988](https://arxiv.org/abs/2403.06988)
- Mündler et al. 2025, [arXiv 2504.09246](https://arxiv.org/abs/2504.09246)
- MGD, [arXiv 2306.10763](https://arxiv.org/abs/2306.10763)
- ChatLSP/Hazel, [arXiv 2409.00921](https://arxiv.org/abs/2409.00921)
- Hoogle+/TYGAR, [arXiv 1911.04091](https://arxiv.org/abs/1911.04091)
- Li, Parsert, Polgreen 2024, [arXiv 2403.03997](https://arxiv.org/abs/2403.03997)
- HySynth, [arXiv 2405.15880](https://arxiv.org/abs/2405.15880)
- Pythia, [arXiv 1912.00742](https://arxiv.org/abs/1912.00742)
- clojure-lsp, [changelog](https://clojure-lsp.io/CHANGELOG/)
- llguidance and XGrammar figures: as cited in `token-level.md` §2

## CLM experiment — bounded run (results)

Run 2026-09-26 on this Mac (one MLX process, ~10 min GPU in total including one
aborted start). Bounded version of the specification above: **150 evals**
(not 500), since 2026-09-20 with ≥ 1 program head, stratified by transcript
(round robin over 373 transcripts, seed 20260926; 150 transcripts, 4,888
eligible evals). Three hole kinds; no enum/arity holes; heads scored on pool B
only (structural combined top-50 ∪ {truth}), never all 10,311.

- **Code and data:** `tmp/next-turn-form-assembly/clm/`
  - `extract.py` (no GPU, 59 s): reruns the unchanged `holes.py` walk and
    rankers, capturing per hole the offset, pool, truth and structural order;
    samples; builds head cards from source (`ns/name [arglists] — first doc
    sentence — :malli/schema`, ≤ 240 chars; clojure.* cards from the
    clojure-1.12.4 jar; 28 of 452 pool vars had no card and use the bare name).
  - `score.py` (the GPU run): `Engine(max_tokens=512)`, `project('state')`
    batched per eval, `project('action')` cached, score = dot.
  - `report.py` → `summary.json`; per-hole ranks in `results.json`; `run.log`.
- **State text:** the previous 3 evals, each with its result head (≤ 300 chars),
  then `;; now writing:` and the eval up to the hole offset, then a one-line
  question per kind (question last, as `clm.schema/state_text` lays out).
  Tail 512 tokens; mean 448 tokens.
- **Candidates:** head card; `key — value schema form` (registry schema text
  when the entry is a bare key); `name = <init form>` (or destructure/fn-param
  description) for locals.
- **Structural arm** on the same holes: combined head score from
  `holes.py:376-377`; keys by past use at that fn/path then declaration order;
  locals most recently bound first. Rank-sum ties count half.

| hole kind | n | arm | top-1 % (95% Wilson) | top-5 % | top-10 % | pre-registered bar |
|---|---|---|---|---|---|---|
| program head, pool B | 492 | structural | 33.3 (29–38) | 50.4 (46–55) | 54.7 (50–59) | top-5 ≥ 60%: **not met** |
| | | CLM | 2.6 (2–4) | 13.2 (11–16) | 25.2 (22–29) | **not met** |
| | | rank-sum | 9.6 (7–12) | 20.5 (17–24) | 37.2 (33–42) | **not met** |
| declared key (all key holes) | 83 | structural | 74.7 (64–83) | 88.0 (79–93) | 90.4 (82–95) | top-1 ≥ 80%: **not met** |
| | | CLM | 36.1 (27–47) | 78.3 (68–86) | 84.3 (75–91) | **not met** |
| | | rank-sum | 42.2 (32–53) | 86.7 (78–92) | 90.4 (82–95) | **not met** |
| declared key (truth declared) | 75 | structural | 82.7 (73–90) | 97.3 (91–99) | 100 (95–100) | — |
| | | CLM | 40.0 (30–51) | 86.7 (77–93) | 93.3 (85–97) | — |
| | | rank-sum | 46.7 (36–58) | 96.0 (89–99) | 100 (95–100) | — |
| local-binding arg | 1,126 | structural | 39.0 (36–42) | 84.3 (82–86) | 96.8 (96–98) | top-1 ≥ 60%: **not met** |
| | | CLM | 26.2 (24–29) | 75.6 (73–78) | 95.4 (94–96) | **not met** |
| | | rank-sum | 27.3 (25–30) | 79.0 (76–81) | 96.6 (95–98) | **not met** |

The sample's structural rates sit near the full-corpus figures (program head
top-5 50% vs 47%; key top-1 75% vs 66%; local top-1 39% vs 42%). Truth was in
the structural top-50 for 73% of program heads; the rest rank 51 structurally.

**Reading.** Zero-shot CLM-8B loses to the structural rankers on every kind
and every cut. Rank-sum with CLM makes each structural arm worse. **No bar is
met, and no arm comes within its interval of one.** This agrees with the
predictability lane's next-file result: the heads were not trained on
"which symbol fills this hole" and do not transfer to it. The candidate-card
format is the one untested lever: keys and locals keep most of their top-5
and top-10 under CLM, so the signal is weak rather than absent. The next step
would be training or fitting a head on these holes, not tuning zero-shot
prompts. First-touch heads were not separated in this run.

**Cost (measured).**

| measure | value |
|---|---|
| state encode, batched per eval, mean 448 tokens | **282 ms per state** |
| per hole, states + cards amortized | 325 ms |
| candidate cards (1,294, ~29 tokens each) | 57 ms per card (20 ms in the aborted first start; the machine was not idle) |
| engine load (warm weights) | 2.6–4.6 s |
| scoring process, end to end | 555 s |
| peak Metal memory | **16.4 GB** (15.6 GB after load) |

Every hole paid a full 512-token state encode. The prefix-KV reuse in the
specification above was not implemented, so these are the no-reuse costs.
