---
type: research
status: complete; read-only prior-art survey; no source changed
created: 2026-09-27
branch: refactor/agent-platform
---
# REPL session prior art — how established systems model a session, its history and its namespace

Date: 2026-09-27. Research lane, read-only. Question: how do established REPL,
notebook and session systems model a session, its history of inputs and
outputs, and its current namespace or context, and how do they persist and
resume it? Seon's refactor target: an agent refs one session entity that holds
its current namespace (updated once at the end of each run of several forms)
and reaches its history of forms, which builds the next context and renders.

Pinned local sources (`reference-code/`, read-only):
`nrepl` f3a0441 (2026-09-22), `clojure` 3bc2b3e9 (2026-05-12), `clerk` 4c25999
(2026-09-14), `portal` 7f12494 (2026-08-14), `datahike` fe1d4e26 (2026-09-26).
Fetched at their default-branch heads on 2026-09-27: CIDER `master` 9e049baa1,
IPython `main` 93dde62b5. Evidence labels used below: **[src]** read in source
at the cited line; **[doc]** stated in official documentation; **[obs]**
observed in files on this machine; **[infer]** my reasoning from the cited
source, not executed.

---

## 1. nREPL (and CIDER on top of it)

**Model.** A session is a `clojure.lang.Atom` holding a map of dynamic Vars to
values (`*ns*`, `*1 *2 *3 *e`, print dials, `*in*`, ...), with metadata
carrying its id, its execution queue and its exec/interrupt/close functions.
All retained sessions live in one process-wide atom keyed by UUID string.
- Roster: `(def ^:private sessions (atom {}))` —
  `nrepl/src/clojure/nrepl/middleware/session.clj:20` [src].
- Baseline bindings: `gather-initial-bindings`, `session.clj:74-117` [src].
- `create-session`, `session.clj:161-179`: copies the source session's map when
  one is given, then `assoc`s a fresh `*in*` and `#'*ns* (create-ns 'user)`
  (`session.clj:174`) [src]. So a clone copies every binding **except** `*ns*`,
  which restarts at `user` [infer from :174 — the `clone` docstring at
  `session.clj:308-310` says the state is a copy].
- Ops: `clone`, `close`, `interrupt`, `ls-sessions` (`session.clj:305-350`);
  a message without `:session` gets an ephemeral session for that one message
  (`session.clj:329-333`) [src].
- `describe` reports `:current-ns` from the session map (`session.clj:351-353`) [src].

**Where the current namespace lives.** In the session map under `#'*ns*`.
Each exec pushes the session map as thread bindings, runs, then merges
`get-thread-bindings` back into the atom, minus `*msg*` and the loader
(`session.clj:201-215`, the swap at `:207-211`) [src]. The namespace therefore
persists because `in-ns`/`ns` `set!` the session's frame and the frame is
written back **once per message** (a message may carry many forms).
- An explicit `:ns` on an `eval` message is pushed as an **inner** binding
  frame (`interruptible_eval.clj:94`, `:125`) and popped in `finally`
  (`:175-181`) before the session write-back, so an explicit `:ns` does not
  become the session's namespace, and an `in-ns` inside such a message is
  discarded with the inner frame [infer from those lines]. The op doc says the
  eval "falls back to `*ns*` for the session" when no ns is given
  (`interruptible_eval.clj:217`) [src].
- Every value response carries `:ns (str (ns-name *ns*))`, i.e. the namespace
  **after that form**, per form not per message (`interruptible_eval.clj:156-158`;
  op doc "`*ns*`, after successful evaluation of `code`", `:223`) [src].
  The protocol overview describes the response `:ns` the same way
  (https://nrepl.org/nrepl/design/overview.html) [doc].

**History.** None on the server. `*1 *2 *3 *e` are the only retained results
(`interruptible_eval.clj:149-151`) [src]. Nothing is stored.

**Resume.** None. Sessions are in-memory threads; server restart loses them
(no persistence code exists in `session.clj`) [src].

**CIDER.** Clones **two** sessions per connection: a main session and a
*tooling* session, so tool evals (completion, pretty printing) do not clobber
the user's `*1 *2` (`lisp/nrepl-client.el:740-763`, `nrepl-tooling-session`
at `:155`) [src]. Namespace choice is client-side: `cider-current-ns` takes a
source buffer's `ns` form, else the buffer-local `cider-buffer-ns`, else the
REPL buffer's, else `"user"` (`lisp/cider-client.el:130-148`) [src]; it is sent
as `"ns"` on each eval (`lisp/nrepl-client.el:1114-1121`) [src]. The REPL
buffer's `cider-buffer-ns` is updated from each response's `ns` slot
(`lisp/cider-eval.el:245`, `:on-ns` at `:264`) [src]. Input history is a
client-side ring persisted optionally to a file (`cider-repl-history-file`,
`cider-repl-history-size` 5000; `lisp/cider-repl.el:1865-1912`) [src] — text
lines only, no namespace, no results.

## 2. clojure.main REPL and prepl

**Model.** No session object. The session **is** the thread's dynamic binding
frame established by `clojure.main/with-bindings` (`*ns*`, `*1 *2 *3 *e`,
print dials; `clojure/src/clj/clojure/main.clj:76-100`) [src]. `repl` runs the
read→eval→print loop inside that frame and `set!`s `*1 *2 *3` / `*e` per form
(`main.clj:431-466`) [src]; the read, eval and print stages are supplied as
option values (`:read :eval :print :caught`, `main.clj:369-414`) [src] — the
same "stage as a value" shape Seon's REPL path uses.

**Current namespace.** `*ns*` in that thread frame; `in-ns` `set!`s it. The
prompt prints it before each read (`repl-prompt`, `main.clj:102-105`) [src].

**prepl.** `clojure.core.server/prepl` (`core/server.clj:194-273`) emits one
`{:tag :ret ...}` map per form with `:val`, `:ns` (the namespace **after**
the form), `:ms`, `:form` (the exact source string from `read+string`) and
`:exception true` on failure; `:out`/`:err`/`:tap` arrive as separate tagged
maps between rets, "exactly one :ret" per eval (`server.clj:196-214`,
`:236-260`) [src]. It starts each connection with `(in-ns 'user)` (`:224`).

**History / resume.** None; the frame dies with the thread/connection.

## 3. Jupyter / IPython

**Kernel session (protocol).** Every message header has `session`, "a unique
entity with state, such as a kernel process or client process"; a
reconnecting client reuses its session id
(https://jupyter-client.readthedocs.io/en/latest/messaging.html) [doc].
`execution_count` increments only for `execute_request`s with
`store_history=True` (forced false when `silent`); it is carried on
`execute_input`, `execute_result` and `execute_reply` and numbers `In[n]` /
`Out[n]` (same page) [doc]. `history_request` supports `range` (session +
start/stop), `tail` (last n) and `search` (glob), optionally with output;
replies are `(session, line_number, input)` or `(session, line, (input,
output))` tuples (same page) [doc]. The kernel's namespace (Python globals)
lives only in the kernel process.

**IPython history database** (`~/.ipython/profile_default/history.sqlite`),
`IPython/core/history.py` [src]:
- `sessions(session integer primary key autoincrement, start timestamp, end
  timestamp, num_cmds integer, remark text)` (`history.py:407-409`).
- `history(session, line, source, source_raw, PRIMARY KEY (session, line))`
  (`:412-414`) — both transformed and raw input kept.
- `output_history(session, line, output text, PRIMARY KEY (session, line))`
  (`:419-421`); output logging is off by default (`db_log_output` False,
  `:741-743`) and stores a repr **string**, not the value (`store_output`,
  `:1145-1165`).
- `new_session` inserts a row and takes `lastrowid` (`:875-887`);
  `end_session` fills `end` and `num_cmds` once at close (`:889-902`); writes
  are batched (`db_cache_size`, background saving thread, `:744-760`,
  `writeout_cache` `:1184+`). A primary-key collision moves logging to a new
  session rather than failing (`:1190-1200`).
- Retrieval by composite key: `get_range` (session, start, stop), `get_tail`,
  `search` (`:967`, `:1051`, `:577`).
- The current working directory history (`dir_hist`) is in memory only
  (`:719-725`); no namespace/context column exists per line.

**Notebook (nbformat).** A notebook is an ordered list of cells; order is list
position. A code cell has `id` (required since 4.5), `source`,
`execution_count` (int or null), `metadata` and `outputs`
(`execute_result` with its own `execution_count`, `display_data`, `stream`,
`error`) (https://nbformat.readthedocs.io/en/latest/format_description.html)
[doc]. So Jupyter keeps **two orders**: document order (cells) and run order
(`execution_count`), and the saved file records only the last run of each
cell. The Jupyter Server `/api/sessions` resource maps a notebook path to a
running kernel (https://jupyter-server.readthedocs.io/en/latest/developers/rest-api.html) [doc].

**Resume.** Reconnecting to a live kernel keeps its state (same kernel process).
A restarted kernel has an empty namespace; the notebook's saved outputs are
shown but the state must be rebuilt by re-running cells. IPython resumes
nothing across launches except input history for recall (`%history -g`).

## 4. Clerk and Portal (Clojure rendering)

**Clerk.** A notebook is a file; parsing yields blocks with ids; analysis
builds a dependency graph and a content hash per code block from its
canonicalized form, the sorted hashes of its dependencies and its vars
(`hash-codeblock`, `clerk/src/nextjournal/clerk/analyzer.clj:625-645`;
`hash` over the topo-sorted graph, `:652-663`) [src]. Results are cached in a
content-addressed store on disk (nippy-frozen, multihash name,
`hash+store-in-cas!`, `eval.clj:46-54`; `->cache-file`, `:35-38`) with a
digest file `@<hash>` pointing at the CAS blob (`read+eval-cached`,
`eval.clj:182-196`) [src]. A value is wrapped `{:nextjournal/value v
:nextjournal/blob-id id}` (`wrapped-with-metadata`, `eval.clj:40-42`) and the
document keeps `:blob->result` (`eval.clj:256`, `:264`) [src]. Forms with ns
effects (`require`, `in-ns`) are marked `ns-effect?` (`analyzer.clj:163`),
evaluated during analysis and never cached (`analyzer.clj:340-341`,
`eval.clj:149-152`) [src]. Namespace: the notebook's `ns` form; analysis binds
`*ns*` for the document (`analyzer.clj:332`) [src]. Viewers are data maps
selected by `:pred` over the value (e.g. `viewer.cljc:434`, `:763`) [src].
**Resume** = re-run the file; unchanged hashes load from the CAS without
re-evaluation. History is not modeled: the document is the only order.

**Portal.** History is an in-memory atom holding a list (newest first) with
viewer metadata (`tap-list`, `portal/src/portal/runtime.cljc:371-373`;
`update-value` conj's, `:401-412`) [src]. The nREPL middleware records **one
map per evaluation**: `:code :ns :file :line :column :result :stdio :report
:time :ms :runtime`, with metadata `{::eval true :portal.viewer/for {...}}`
choosing per-key viewers, then `tap>`s it (`portal/src/portal/nrepl.clj:105-144`)
[src]. It also keeps its own ns in the nREPL session map
(`swap! session assoc #'*portal-ns* namespace`, `nrepl.clj:250-251`) [src].
No persistence; closing the runtime loses the list.

## 5. Datomic / Datahike modeling guidance

- **Transaction entity as the event record.** Every transaction is an entity
  with `:db/txInstant`; users may add facts to it ("Add Facts About the
  Transaction Entity": when, who, where, why —
  <https://docs.datomic.com/reference/best.html>) [doc]. Datahike has the same
  meta entity and joins over `?tx` (`datahike/doc/time_variance.md:225-263`)
  and strictly monotonic auto `:db/txInstant` (`:266-280`) [src].
- **Current state vs history.** A database value already answers "current"
  (`d/db`) and "what was" (`as-of`, `history`, `since`;
  `time_variance.md:54-224`) [src]. Datomic advises `:db/noHistory` for
  high-churn attributes you will not query historically ("Use NoHistory for
  High-Churn Attributes", best.html) [doc]; Datahike honours `:db/noHistory`
  per attribute when writing (`src/datahike/db/transaction.cljc:448`,
  `db/utils.cljc:48-50`) [src].
- **Refs in one direction.** "Model Relationships in One Direction Only" —
  the reverse index gives the other direction (best.html) [doc].
- **Cardinality-many is a set; order must be explicit.** Component entities
  (`:db/isComponent true`) are created by nested maps, pulled automatically
  and retracted with their parent (https://blog.datomic.com/2013/06/component-entities.html;
  Datahike schema doc `doc/schema.md:76`) [doc]. Cardinality-many values have
  no order; the community answers are a position attribute, a linked list or
  a serialized value, chosen by whether you must query inside the list
  (https://forum.datomic.com/t/handling-ordered-lists/305;
  <https://www.augustl.com/blog/2013/ordering_cardinality_many_in_datomic/>)
  [doc]. For an **append-only** log the position never changes, so the
  mid-list-insert cost those threads worry about does not arise [infer].
- **Ordered retrieval.** Datahike offers `index-range` over an indexed
  attribute and `seek-datoms`/`rseek-datoms` for forward/backward index walks
  (`src/datahike/core.cljc:160-185`) [src]; `rseek-datoms` gives "last n"
  without scanning the whole history. AVET needs `:db/index` (or unique/ref)
  (`db/utils.cljc:40-42`, `:316-319`) [src].
- **Composite keys.** Tuples (`:db/tupleAttrs`) give a unique compound key
  such as (session, ordinal) (`doc/schema.md:77-128`) [src].
- **Retention.** Retraction keeps history; `:db.history.purge/before` and
  `d/gc-storage` reclaim (`time_variance.md:301-372`, `doc/gc.md`) [src].
- "Use Consistent Db Value for Unit of Work"; prefer `t` over `txInstant` for
  precise `as-of` (best.html) [doc].

## 6. AI coding agent frameworks

- **clojure-mcp** (https://github.com/bhauman/clojure-mcp): evaluates over an
  nREPL connection, reporting the response `:ns` in a divider line
  (`format-divider`, `src/clojure_mcp/tools/eval/core.clj`); no evaluation
  history is stored; sessions die with the nREPL process; an optional
  scratch-pad file is the only persistence [doc — README and source page
  summaries, not read line by line].
- **Claude Code** transcripts (this machine,
  `~/.claude/projects/-Users-sean-src-seon/*.jsonl`) [obs]: one append-only
  JSONL file per session; every record carries `uuid`, `parentUuid` (a chain,
  so branches/sidechains are possible, `isSidechain`), `sessionId`,
  `timestamp`, and the **context it ran in** — `cwd` and `gitBranch` — on
  each record. Resume replays the chain from a leaf. Format is undocumented and
  version-dependent; read as observation only.
- **Codex CLI** rollouts (`~/.codex/sessions/**.jsonl`) [obs]: one
  `session_meta` record (id, initial `cwd`, git, instructions), then one
  `turn_context` record **per turn** (`cwd`, model, effort, policies,
  `turn_id`) followed by that turn's `response_item`/`event_msg` records, plus
  `compacted` records that replace older history. Context is stated per turn,
  not per item.

---

## Comparison

| System | Session entity | Where current ns/context lives | Update grain | History order | History stored | Resume |
|---|---|---|---|---|---|---|
| nREPL | atom of Var→value, UUID, process roster | `#'*ns*` in session map | written back once per message (`session.clj:207`) | none | no (`*1..*3` only) | no |
| CIDER | two nREPL sessions (main, tooling) | client buffer ns, sent as `:ns` per eval | per response `:ns` | client ring | text-only file, optional | history recall only |
| clojure.main / prepl | thread binding frame | `*ns*` binding | per form | none | no; prepl emits `:ns :form :ms` per `:ret` | no |
| IPython | `sessions` row (start, end, num_cmds) | kernel globals; none in DB | `end_session` once at close | `(session, line)` PK | input source+raw; output repr off by default | inputs recalled; state lost |
| nbformat | notebook file | kernel | — | list order + `execution_count` | last run per cell, outputs | re-run cells |
| Clerk | notebook file | the file's `ns` form | per analysis | document order | CAS by form+deps hash | re-run; cache hits skip eval |
| Portal | in-memory list | `*portal-ns*` in nREPL session | per response | newest-first list | one map per eval (`:code :ns :result :ms`) | no |
| Claude Code | JSONL file per session | `cwd`, `gitBranch` on **every** record | per record | `parentUuid` chain | every message | replay chain |
| Codex | `session_meta` + JSONL | `turn_context` per turn | per turn | file order; `compacted` | every item | replay; compaction |
| Datomic/Datahike | any entity; tx entity for provenance | current db value; `noHistory` for churn | per transaction | explicit ordinal / index range | full history unless purged | the db is the state |

## Lessons for Seon

1. **Keep the current namespace on the session entity and write it once per
   run.** nREPL persists `*ns*` by writing the binding frame back once per
   message (`session.clj:201-215`), not per form; that is the same grain as
   "once at the end of each run". Evidence: [src] nREPL. Consider
   `:db/noHistory` on that attribute only if it churns and nobody asks its
   history — the per-form record (lesson 2) already answers "which ns was
   current then" [doc] Datomic best practices, [src] `transaction.cljc:448`.
2. **Record on each evaluated form the namespace it ran in (and after).**
   prepl's `:ret` and nREPL's value response both report `*ns*` per form
   (`server.clj:242-248`, `interruptible_eval.clj:156`); Claude Code stamps
   `cwd` on every record [obs]. Then the session's current ns is a
   denormalized head of the latest record's ns and is drift-checkable against
   it (Seon law: mirrors are derived, drift-checked). Seon already has
   `:seon.cluster.eval/ns` (`resources/seon/schemas/seon.cluster.eval.edn:46`).
   Evidence: [src] clojure, nrepl; [obs] Claude Code.
3. **History as entities with an explicit ordinal under a unique compound key,
   not an ordered cardinality-many.** Every durable system orders by an
   explicit number (IPython `(session, line)` PK, `history.py:412-414`;
   Jupyter `execution_count`); Datomic cardinality-many is a set and needs a
   position attribute (forum thread, augustl). For an append-only log the
   position never moves. Use a tuple (session, ordinal) or (run, ordinal) as
   `:db.unique/identity` and walk it with `index-range`/`rseek-datoms` for
   "last n" (`core.cljc:160-185`). Seon already has
   `:seon.cluster.eval/ordinal` and `/run` (`seon.cluster.eval.edn:39-41`).
   Evidence: [src] IPython, Datahike; [doc] Datomic forum.
4. **Point from the record to the session (one direction), not a growing
   component list on the session.** "Model relationships in one direction" —
   the reverse index gives session → forms without a cardinality-many that
   grows forever on one entity [doc] best.html. Use `isComponent` only where
   retracting the session should cascade (it will, for components,
   blog.datomic.com component entities) — decide that by retention (lesson 7),
   not by pull convenience. Evidence: [doc].
5. **Keep document order and run order distinct.** Jupyter carries both (cell
   list vs `execution_count`) and loses history because the file keeps only the
   last run of each cell (nbformat doc). Seon's history is run order; anything
   that later presents forms "by namespace" or "by definition" is a derived
   view, never a second store. Evidence: [doc] nbformat.
6. **Store the value's identity, not its printed text, and cache renders by
   content.** IPython stores output as an optional repr string
   (`history.py:1145-1165`) and so cannot re-render; Clerk keys results by a
   hash of canonical form + dependency hashes and stores blobs in a CAS
   (`analyzer.clj:625-645`, `eval.clj:46-54`); Portal chooses viewers by value
   metadata (`nrepl.clj:138-143`). For Seon: the eval record refs the value's
   blob digest; shown text is a render keyed by (value digest, renderer
   digest) in `seon.cache`. Clerk's rule that ns-effect forms are never cached
   (`eval.clj:149-152`) carries over: a render or derivation that depends on
   the current ns must key on it. Evidence: [src] IPython, Clerk, Portal.
7. **Resume from facts, never from live state; retention is a policy on the
   log.** No surveyed REPL resumes its evaluator state: nREPL, prepl, Portal,
   clojure-mcp lose it; Jupyter re-runs cells; Clerk re-runs with a cache;
   agent CLIs replay transcripts. This matches Seon's "recover facts, not
   execution": the session entity plus its ordered forms rebuild context; the
   SCI world is reacquired. Codex's `compacted` records show the third need —
   an explicit compaction marker so older history can be summarized without
   rewriting it; Datahike's `:db.history.purge/before` + `gc-storage`
   (`time_variance.md:301-372`) is the retention seam. Evidence: [src] nrepl,
   clojure, portal; [doc] Jupyter; [obs] Codex.
8. **Separate the agent's session from tool/system evaluations.** CIDER clones
   a tooling session so pretty-printing and completion evals do not move `*1`
   (`nrepl-client.el:740-763`); Jupyter's `silent`/`store_history=false` keeps
   system executes out of `execution_count`. Seon's render-form evaluations
   (read-only, in the viewing agent's world) should neither advance the
   session's ordinal nor change its current ns. Evidence: [src] CIDER; [doc]
   Jupyter messaging.

Falsifiers worth a probe before adopting: (a) confirm at the REPL that one
run's forms all share a basis so a single end-of-run ns write is exact (a form
that `in-ns`es mid-run must still record its own ns per lesson 2); (b) measure
`rseek-datoms` over a (session, ordinal) tuple vs a pull of the whole history
on a real agent with thousands of forms; (c) check whether any consumer reads
the session ns's history — if none, `:db/noHistory` is safe.

Sources fetched: nrepl.org design overview; jupyter-client messaging;
nbformat format description; jupyter-server REST API; IPython
`core/history.py` (main 93dde62b5); CIDER `lisp/*.el` (master 9e049baa1);
docs.datomic.com/reference/best.html; blog.datomic.com component entities;
forum.datomic.com "Handling ordered lists"; augustl.com cardinality-many
ordering; github.com/bhauman/clojure-mcp.
