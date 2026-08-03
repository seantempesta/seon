---
type: research
status: active
tags: [research, agent, toolkit, capability]
---

# Agent tools design: files, structural editing, shell, and effect facts

## Decision

The first agent-tools slice should ship three layers in dependency order:

1. `my.fs` is the byte-honest filesystem primitive: bounded `read`,
   conditional `write`, bounded `glob`, and no-follow `stat`.
2. `my.edit` is the normal source-editing surface. Clojure files are changed as
   parsed forms through rewrite-clj; exact-string and guarded line-window edits
   remain explicit text operations for other files. No structural failure
   silently falls back to text editing.
3. `my.shell/run` executes one argv vector on the `:io` executor, drains both
   output streams without a heap-sized capture, and returns complete inline or
   blob-backed byte evidence.

Every public capability leaf declares both facts in its `defn` metadata:

```clojure
{:seon.workload :io
 :seon.effect/capability seon.fs.jvm/read}
```

The function row's `:seon.fn/sym` is the capability identity. The qualified
symbol stored in `:seon.effect/capability` is the protected platform handler.
The index lifts both metadata values into program facts. Effectfulness,
capability identity, handler resolution, workload, placement, and pure-contract
admissibility then derive from Datalog reachability; no family enum, public
function map, namespace-prefix rule, or dispatch naming convention exists.

This is a fresh design. The old filesystem and shell code supplies failure
evidence only. In particular, there are no per-agent grants, environment-backed
runtime config, mutable job registry, automatic fuzzy matching, or swallowed
output.

## Dependency ledger

### First-party owners and current gaps

| Mechanism | Current evidence | Contract this design relies on |
|---|---|---|
| Program graph index | `src/seon/fn.clj:239-273` lifts Malli schemas, call edges, and `:seon.workload`; `resources/seon/schema.edn:2009-2025` declares the function row | Extend the same index pass with one metadata projection; do not create another scanner or registry. |
| Capability-free admission | `src/seon/cluster/loop.clj:337-363` currently treats any reachable workload leaf as a capability | Query `:seon.effect/capability` instead. Workload and effectfulness are independent facts. |
| Flat errors | `resources/seon/schema.edn:997-1005` defines `:seon.error/value` | Every tool returns its success value or this flat error value. No `ok?` envelope and no exception crosses into the run loop. |
| Effect owner | No `src/seon/effect.clj` exists; the zero-arm proof is in `agent-tools-quarry-2026-08-03.md:110-126` | `seon.effect/request!` must land before any platform handler becomes callable. One request identity and one receipt path cover every family. |
| Filesystem quarry | `src-old/seon/agent/fs/leaf.clj:44-95,139-167,239-327,331-411` | Retain scope checks, no-follow traversal, bounds, digests, ambiguity refusal, and syntax refusal; delete atoms, env config, whole-file reads, lossy decoding, regex editing, and direct writes. |
| Shell quarry | `src-old/seon/agent/shell/leaf.clj:46-123,125-150,169-307` | Retain argv, cwd, stdin, concurrent draining, exit evidence, and timeout cleanup; delete grants, literals, lossy UTF-8 capture, process-local jobs, polling, and background APIs. |
| Blob owner | `src/seon/blob.clj:15-68` accepts complete UTF-8 strings; `reference-code/konserve/src/konserve/protocols.cljc:42-44` and `konserve/core.cljc:633-669` accept JVM `InputStream`, `File`, byte array, or string | Add a binary streaming/staging API at `seon.blob` before shell. The current `put!` cannot honestly carry large command output. |
| Config facts | `resources/seon/schema.edn:510-750` and `config/default.edn:1-90` are the declaration/default authorities | File and shell ceilings are config dials with measured defaults. Runtime leaves never read environment variables or contain fallback literals. |
| Workload substrate | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:122-134`; `src/seon/flow.clj` | Blocking filesystem, process, and blob work runs on `:io`; pure rewrite-clj transforms remain ordinary compute code. |

The effect architecture text at `docs/seon/architecture/toolkit.md:132-148`
still describes four replay classes. That text is older than the standing
crash rule: nothing re-executes. These tool contracts therefore declare no
replay class. A dangling effect receipt becomes `:interrupted`; a run adapts
from facts and does not refire it. The architecture owner should reconcile
that paragraph before implementation, but the discrepancy does not block this
specification.

### What open agent harnesses converged on

| Harness | Source-grounded behavior | Lesson retained here |
|---|---|---|
| Aider | Aider exposes whole-file, SEARCH/REPLACE-block, and simplified unified-diff formats, choosing among them by model. Its own benchmark found unified diff materially better for one GPT-4 Turbo workload. See [Aider edit formats](https://aider.chat/docs/more/edit-formats.html) and [the unified-diff experiment](https://aider.chat/docs/unified-diffs.html). | Models benefit from familiar, compact edits over substantive code blocks. Unified diff is useful model syntax, but it is not the execution contract when a real parser can identify Clojure forms. |
| Claude Code | `Edit` requires a prior current read, exact `old_string`, and uniqueness unless `replace_all` is explicit. `Glob` is capped and announces truncation. See [Claude Code tools reference](https://code.claude.com/docs/en/tools-reference). | Keep current-content fencing, exactness, explicit replace-all, bounded discovery, and honest incomplete results. Represent the current-read fence as a digest, not conversation-local hidden state. |
| OpenHands | The current SDK exposes a dedicated `FileEditorTool`; the older ACI's string-replace editor was migrated into that SDK. See [OpenHands SDK](https://github.com/OpenHands/software-agent-sdk) and [the ACI migration notice](https://github.com/OpenHands/openhands-aci). | Editing remains a first-class tool beside terminal execution rather than a shell incantation. Keep the operation structured and its result reviewable. |
| SWE-agent | The vendored editor exposes `view`, `create`, exact `str_replace`, line `insert`, and `undo`; long views direct the model toward grep and a line window (`reference-code/swe-agent/tools/edit_anthropic/config.yaml:1-55`; `.../bin/str_replace_editor:27-35,438-514,516-631`). Its search helper uses grep line evidence and refuses more than 100 matching lines (`reference-code/swe-agent/tools/search/bin/search_file:27-52`). | The useful loop is narrow discovery, bounded view, exact edit, and local evidence. Do not copy its tab expansion, encoding fallbacks, mutable undo history, shell construction, or prose errors. |
| Clojure MCP | The vendored project uses rewrite-clj to identify and edit forms and s-expressions (`reference-code/clojure-mcp/src/clojure_mcp/tools/form_edit/core.clj:1-14,109-174,176-241`; `.../tool.clj:53-124`; `.../combined_edit_tool.clj:106-159`). | This proves the model-facing form-edit shape is practical. Seon should keep the rewrite-clj core, while replacing string-built dispatch selectors, mutable search queues, implicit formatting, exception envelopes, and regex normalization with data and flat errors. |

The convergence is narrower than “apply patches.” Successful harnesses expose
a bounded read/search loop, fence edits against current content, refuse
ambiguous replacement, and return nearby evidence. Seon can preserve those
properties while raising the normal abstraction from lines to Clojure forms.

### Third-party package decisions

Training-data familiarity is a real selection criterion: an agent should be
able to author ordinary library calls from well-known examples. It is not a
license to add overlapping owners. A package is selected only where its API
also fits Seon's data and error boundaries.

| Package | Maturity and API fit | Decision and exact source requirement |
|---|---|---|
| rewrite-clj | The project dates to 2013, describes v1 as widely adopted, preserves whitespace/comments, and exposes nodes, parsers, and zippers (`reference-code/rewrite-clj/README.adoc:25-31`; `doc/01-user-guide.adoc:33-67,104-125,147-180`). It is used by cljfmt, clojure-lsp, refactor-nrepl, Babashka, and Clojure MCP. Pure parse/transform failures wrap naturally as flat errors. | **Adopt for `seon.edit` pure form transforms.** The checkout is `60782e501aaf312cb90c9ff0bee05d5da5125563` (`v1.2.51-5-g60782e5`); upstream `v1.2.55` peels to `99bdfb2b3f8b775b4936521c87d11341cca755d1`. Advance the existing `reference-code/rewrite-clj` submodule to that tag only after its tests and Seon's lossless falsifiers pass. Move it from test-only to the production dependency set; do not copy source. |
| babashka.fs | A focused JDK NIO wrapper with familiar `path`, `glob`, stat, link, and traversal functions. The recent changelog still exercises symlink behavior and glob semantics; its public API throws, follows links in some operations, and does not enforce Seon's root policy. | **Adopt as a protected JVM convenience, not as policy.** Use the nested checkout `reference-code/babashka/fs` at `v0.5.33`, commit `3fdcbcb8de6af0c880a0082700a295c55ffd2ecd`. Seon's handler owns canonical roots, no-follow traversal, byte limits, conditional writes, and flat errors. Do not bind babashka.fs directly into SCI. |
| babashka.process | `process` returns streams and a dereferenceable process record; `:out :bytes` exists, stdout/stderr are drained concurrently for captured modes, and `destroy-tree` is provided (`reference-code/babashka-process/src/babashka/process.cljc:117-148,360-451,453-470`; `README.md:150-201,303-321`). Timed deref only returns the timeout value; it does not terminate the process. `shell` tokenizes a string and throws on nonzero exit (`process.cljc:674-708`). | **Adopt `process`, never `shell` or `check`.** The existing `reference-code/babashka-process` pin is `16a84e0af0da51b8c84e289970f6b7cc35b35d18` (`v0.6.25`). Seon supplies argv only, event/timeout cleanup, streamed sinks, sanitized environment, and error conversion. |
| Hato | A Ring-shaped synchronous/async client over JDK 11 `HttpClient`, with a stable `1.0.0` release and broad Clojars use. Its synchronous form matches virtual-thread `:io` execution and exceptions can be flattened at one handler. See [Hato 1.0.0](https://cljdoc.org/d/hato/hato/1.0.0) and [Hato's repository](https://github.com/gnarroway/hato). | **Preferred candidate for the later outbound `my.web` handler**, subject to a direct comparison with the current JDK client in `seon.ai`. Vendor `reference-code/hato` at `v1.0.0`, commit `8c80539c7fce9fa92320fa711d9c22ff78e7d3dd`, before implementation. Do not create a second retry or redirect policy in the wrapper. |
| http-kit client | Mature and well-known, but its callback/future client shape is unnecessary in fresh synchronous CLJ. Seon's maintained fork is already the Datastar SSE server dependency (`deps.edn:60-71`) at `238a85cc555a38892f2f9a7583c9cf5cec0fb201`. | **Do not expand it into the agent HTTP client.** Keep `reference-code/http-kit` for its existing web UI owner. A second use would couple agent fetch policy to the server fork and reintroduce async coloring. |
| jsoup | A long-lived Java HTML5 parser with CSS selection, text extraction, cleaning, and extensive real-world familiarity. Its DOM objects are tier-local and exceptions are easy to flatten. See [jsoup](https://jsoup.org/) and [jsoup source](https://github.com/jhy/jsoup). | **Adopt for later `my.web` extraction.** Vendor `reference-code/jsoup` at `jsoup-1.22.2`, peeled commit `ac28afe6e5bf96d39fd17c3e0a797a7585e1958c`. The handler immediately projects elements into ordinary namespaced maps/strings; no `Document`, `Element`, stream, or selector object crosses the boundary. |
| Hickory | Clojure-friendly HTML-to-data and selector APIs, but it already depends on jsoup and adds a second tree vocabulary. Its lower usage and thinner release cadence make it less familiar than jsoup itself. See [Hickory source](https://github.com/clj-commons/hickory). | **Do not adopt initially.** If a later extractor demonstrates that Hickory eliminates substantial first-party projection logic, vendor `reference-code/hickory` at `Release-0.7.7`, commit `faf4a95143c3109692e2d5747e722088d8540e08`, and replace—not accompany—the homegrown projection. |
| clj-yaml | Focused SnakeYAML wrapper maintained in clj-commons and shipped in Babashka. Parsing and generation are ordinary value transformations, though aliases, constructors, and input size require explicit bounds. See [clj-yaml](https://github.com/clj-commons/clj-yaml). | **Adopt when YAML enters the program graph.** Vendor `reference-code/clj-yaml` at `v1.0.29`, peeled commit `57c817a20910003583b0b0dde16a76ee101fd7e7`, plus the exact transitive SnakeYAML source. Expose pure string-to-data/data-to-string functions; filesystem access composes through `my.fs`. Catch parser failures as `:seon.error/value`; use safe constructors only. |
| data.csv | Official, narrow Clojure library with familiar lazy row APIs and no reason to recreate CSV quoting. The lazy reader lifetime is the main boundary hazard. See [data.csv](https://github.com/clojure/data.csv). | **Adopt for bounded in-memory or streaming parse functions.** Vendor `reference-code/data.csv` at `v1.1.1`, peeled commit `d832f3ae8bce439313c06cd8f3cd466af27834f3`. A protected function owns the reader lifetime and returns bounded realized rows or a blob descriptor; no lazy sequence or `Reader` escapes. File access remains `my.fs`. |

The first production dependency wave therefore needs only rewrite-clj,
babashka.fs, and babashka.process. Hato and jsoup belong to the subsequent web
slice. clj-yaml and data.csv are ordinary library accretions when a real use
case demands them. Hickory and the http-kit client are explicitly not selected.

### Package admission falsifiers

- Run rewrite-clj's own suite at the selected pin, then prove byte identity for
  a no-op parse/render corpus containing reader conditionals, metadata,
  comments, commas, tagged literals, CRLF, and a missing final newline.
- Run babashka.fs's suite and Seon's root/symlink fixtures. Any operation used
  by the handler must demonstrate its link behavior from source and a probe;
  an undocumented default is not admitted.
- Run babashka.process's suite, then prove argv identity, separate concurrent
  stream draining, timed-deref non-termination, and explicit `destroy-tree`
  behavior on the shipped JDK.
- Before selecting Hato for `my.web`, drive the same redirect, streaming body,
  cancellation, TLS, header, and private-address policy cases through Hato and
  the current JDK client. Select one transport owner from the evidence.
- Parse a fixed malformed/real-world HTML corpus with jsoup and Hickory. jsoup is
  retained only if the ordinary-data projection stays smaller and clearer than
  adopting Hickory's second tree vocabulary.
- Exercise clj-yaml with aliases, custom tags, deeply nested input, duplicate
  keys, and oversized scalars. Only the safe, bounded constructor path is
  admissible.
- Exercise data.csv with quoted newlines, escaped quotes, alternate separators,
  malformed rows, early termination, and a reader that records close. No lazy
  row may outlive its handler.

### What not to build from packages

- no copied or partially vendored library source;
- no dependency without its exact source submodule and transitive-source
  ledger;
- no direct package namespace in SCI when the package performs a capability;
- no Java, stream, parser-node, DOM, process, reader, or lazy-sequence object in
  an agent result;
- no second HTTP owner, HTML tree, filesystem policy, subprocess wrapper, CSV
  parser, or YAML parser beside the selected dependency; and
- no “familiar” package whose API forces async coloring, exceptions, mutable
  state, or a duplicate registry into the public contract.

## Shared capability and error contract

### Declared capability fact

Add these canonical declarations:

```clojure
:seon.effect/capability :qualified-symbol

:seon.fn/fn
[:map {:seon.db/entity true}
 ;; existing attributes remain
 [:seon.effect/capability {:optional true} :seon.effect/capability]]
```

The metadata value names the protected JVM handler. The public capability
function remains the owner and passes its own Var to `effect/request!`:

```clojure
(defn read
  {:malli/schema [:=> [:cat :my.fs/read-request]
                  [:or :my.fs/read-result :seon.error/value]]
   :seon.workload :io
   :seon.effect/capability 'seon.fs.jvm/read}
  [request]
  (effect/request! #'read request))
```

The quote is required Clojure source syntax for metadata naming a private Var;
the runtime metadata value and the indexed value are both the qualified symbol
`seon.fs.jvm/read`. This is declaration, not a registration table. `var-row`
accepts only a qualified handler symbol, stores it on the same `:seon.fn`
entity, and refuses these malformed states during indexing:

- a capability marker without `:seon.workload`;
- a capability workload other than `:io` for this initial host-tool slice;
- a handler symbol that is missing, public, un-schema'd, or itself marked as a
  capability;
- a direct call to `seon.effect/request!` from an unmarked function; or
- a marked function whose body never reaches `seon.effect/request!`.

The final two checks use the program graph's call edges. They are not source
text searches. Dynamic unresolved calls fail closed as `:mixed` and
effect-unknown.

For a root function `f`:

- `capabilities(f)` is the set of `:seon.fn/sym` values on reachable rows that
  carry `:seon.effect/capability`;
- `effectful?(f)` is `(seq (capabilities f))`;
- pure-contract admissibility requires that set to be empty and every reached
  call edge to resolve;
- workload continues to derive from reachable workload leaves: only `:io` is
  `:io`, only `:compute` is `:compute`, both are `:mixed`, and unresolved is
  `:mixed`; and
- effect dispatch resolves the handler from the reached owner row, never from
  a case expression, map, namespace prefix, or constructed symbol.

`src/seon/cluster/loop.clj:337-363` must consequently stop using
`:seon.fn/workload` as a proxy for effectfulness. A blocking pure helper may be
`:io` without being a capability; a declared capability remains effectful
because its own fact says so.

### Common request behavior

All capability entries follow one sequence inside `seon.effect`:

1. derive `(run, form ordinal, effect ordinal)` from the current evaluation;
2. query the owner function row and resolve its declared handler;
3. validate the request against the owner's Malli input schema;
4. commit the one open effect receipt before external dispatch;
5. run the handler on `:io` with the effective cluster config facts;
6. project its success or flat error into ordinary bounded data; and
7. settle the receipt once with that result.

An interrupted process marks an open receipt `:interrupted`. It does not run
the handler again. Read operations are cheap enough to request anew from a new
form; that is not replay of the old effect identity.

Every error has exactly the shared floor:

```clojure
{:seon.error/kind :my.fs/not-found
 :seon.error/message "File does not exist."
 :seon.error/data {:my.fs/path "src/example.clj"}}
```

The namespaced `:seon.error/kind` is the failed rule. Data carries only bounded
ordinary values such as the path, expected/actual digest, match count,
candidate line positions, exit evidence, or output descriptors. Java class
names, stack traces, `Path`, stream, process, zipper, and parser node objects
remain core-fault evidence and never enter an agent error.

### Effect metadata falsifiers

- Index one marked leaf and query its owner symbol, handler symbol, schema, and
  workload from one function entity.
- Index a pure caller of that leaf and prove the reachability query returns the
  leaf's owner symbol without any namespace or family input.
- Remove the marker while leaving `effect/request!`; indexing must refuse the
  source rather than silently classifying it pure.
- Add a marker with an unresolved handler; indexing must refuse publication.
- Give a pure blocking helper `:seon.workload :io` without a capability marker;
  it must classify as blocking but remain capability-free.
- Give a caller one compute leaf and one capability leaf; workload must be
  `:mixed` and capability reachability must still name only the capability.
- Make a call edge unresolved; both placement and contract admissibility must
  fail closed without inventing a capability identity.

### What not to build at the shared boundary

- no capability family enum or public-functions map;
- no per-agent grants or different callable function sets;
- no second handler registry, multimethod, protocol table, or name-derived
  dispatch;
- no replay-class annotation, retry flag, or automatic refire;
- no direct platform handler binding in SCI;
- no exceptions, Java objects, or unbounded values in agent results; and
- no workload test used as a substitute for the explicit capability fact.

## `my.fs`: bounded byte-honest filesystem

### Ownership and path law

`my.fs` owns the public schemas and capability functions. A pure portable core
validates ordinary request relationships. `seon.fs.jvm` is the single platform
handler family and may use babashka.fs plus JDK NIO. It does not become an
agent namespace.

Every request is evaluated against cluster filesystem policy facts. These are
capability policy, not grants: every agent may call `my.fs`, and the same
declared roots and limits determine what the filesystem capability will do.
At minimum the policy declares roots, maximum read bytes, maximum inline bytes,
maximum glob results, and maximum traversal entries. Defaults live in
`config/default.edn`; the leaf reads the effective database facts once per
request.

The handler applies this path law before opening anything:

1. parse the supplied string into a `Path`, make it absolute against the
   declared working root, and normalize lexical `.`/`..` segments;
2. choose the deepest declared root that contains the lexical path;
3. obtain no-follow attributes for every existing segment from that root;
4. refuse every symbolic link, including the final path and a broken link;
5. for a creation, apply the same walk to the existing parent and create the
   staged file in that parent; and
6. immediately before the final write move, repeat the parent/final no-follow
   checks and the content precondition.

There is no `toRealPath`-then-trust gap and recursive traversal never requests
`FOLLOW_LINKS`. An out-of-root path, symlink, non-directory parent, or changed
parent returns a flat error before mutation.

### Shared filesystem schemas

These schema sketches use open Malli maps. Unknown keys are ignored; declared
keys and cross-key predicates remain rigorous.

```clojure
:my.fs/path [:string {:min 1}]
:my.fs/byte [:int {:min 0 :max 255}]
:my.fs/byte-count [:int {:min 0}]
:my.fs/byte-offset [:int {:min 0}]
:my.fs/bytes [:vector :my.fs/byte]
:my.fs/digest :seon.blob/digest

:my.fs/content
[:and
 [:map
  [:my.fs/text {:optional true} :string]
  [:my.fs/bytes {:optional true} :my.fs/bytes]
  [:seon.blob/digest {:optional true} :seon.blob/digest]]
 [:fn my.fs/content?]]

:my.fs/write-precondition
[:and
 [:map
  [:my.fs/expected-absence? {:optional true} [:= true]]
  [:my.fs/expected-digest {:optional true} :my.fs/digest]]
 [:fn my.fs/write-precondition?]]

;; Each predicate requires exactly one declared arm. Other keys stay open.
(defn content?
  [value]
  (= 1 (count (filter #(contains? value %)
                      [:my.fs/text :my.fs/bytes :seon.blob/digest]))))

(defn write-precondition?
  [value]
  (= 1 (count (filter #(contains? value %)
                      [:my.fs/expected-absence? :my.fs/expected-digest]))))
```

`:my.fs/expected-absence?`, when present, must be exactly `true`.
`:my.fs/expected-digest` reuses the global blob digest shape. A caller cannot
request an unconditional overwrite.

### `my.fs/read`

```clojure
:my.fs/read-request
[:map
 [:my.fs/path :my.fs/path]
 [:my.fs/byte-offset {:optional true} :my.fs/byte-offset]
 [:my.fs/max-bytes {:optional true} [:int {:min 1}]]
 [:my.fs/encoding {:optional true} [:enum :bytes :utf-8]]]

:my.fs/read-result
[:map
 [:my.fs/path :my.fs/path]
 [:my.fs/digest :my.fs/digest]
 [:my.fs/file-bytes :my.fs/byte-count]
 [:my.fs/byte-offset :my.fs/byte-offset]
 [:my.fs/bytes-read :my.fs/byte-count]
 [:my.fs/eof? :boolean]
 [:my.fs/bytes {:optional true} :my.fs/bytes]
 [:my.fs/text {:optional true} :string]]
```

The effective byte limit is the smaller of `:my.fs/max-bytes`, when supplied,
and the cluster ceiling. Absence uses the cluster ceiling. Offsets and lengths
are bytes, never characters. `:bytes` returns integer octets. `:utf-8` decodes
only when the requested byte window begins and ends on valid UTF-8 boundaries;
otherwise it returns `:my.fs/invalid-utf8-window` and suggests `:bytes`.

The handler streams the complete open file once, computing SHA-256 and total
bytes while retaining only the requested bounded window. Attributes sampled
before and after the pass must agree; otherwise return
`:my.fs/changed-during-read`. Thus the digest and window describe one observed
byte stream, not a stat from one moment and content from another. `:my.fs/eof?`
is true only when the returned window reached that stream's end.

Errors: `:my.fs/path-refused`, `:my.fs/not-found`, `:my.fs/not-regular-file`,
`:my.fs/read-limit`, `:my.fs/invalid-utf8-window`,
`:my.fs/changed-during-read`, and `:my.fs/read-failed`.

### `my.fs/write`

```clojure
:my.fs/write-request
[:map
 [:my.fs/path :my.fs/path]
 [:my.fs/content :my.fs/content]
 [:my.fs/precondition :my.fs/write-precondition]]

:my.fs/write-result
[:map
 [:my.fs/path :my.fs/path]
 [:my.fs/created? :boolean]
 [:my.fs/changed? :boolean]
 [:my.fs/before-digest {:optional true} :my.fs/digest]
 [:my.fs/after-digest :my.fs/digest]
 [:my.fs/bytes-written :my.fs/byte-count]]
```

Text means exact UTF-8 bytes. A byte vector is checked against the configured
write ceiling before allocation. A blob source is copied and verified through
the binary blob owner. The handler computes the proposed digest first, then:

- `expected-absence?` succeeds only when no final path, including a symlink,
  exists;
- `expected-digest` succeeds only when the current regular file's streamed
  digest equals it; and
- an equal proposed digest returns `changed? false` without replacing the
  file.

A real write uses a create-new staged file in the target directory, streams
and hashes the content, forces the file, repeats the path/precondition checks,
and performs one atomic same-filesystem move. If the filesystem cannot provide
the promised atomic move, return `:my.fs/atomic-write-unsupported`; do not
quietly downgrade. Cleanup removes only the explicitly created staged path and
never follows a link.

Errors: the read errors plus `:my.fs/already-exists`, `:my.fs/stale-digest`,
`:my.fs/write-limit`, `:my.fs/blob-unavailable`,
`:my.fs/atomic-write-unsupported`, and `:my.fs/write-failed`.

### `my.fs/glob`

```clojure
:my.fs/glob-request
[:map
 [:my.fs/root :my.fs/path]
 [:my.fs/pattern [:string {:min 1}]]
 [:my.fs/max-depth {:optional true} [:int {:min 0}]]
 [:my.fs/max-results {:optional true} [:int {:min 1}]]]

:my.fs/glob-result
[:map
 [:my.fs/root :my.fs/path]
 [:my.fs/paths [:vector :my.fs/path]]
 [:my.fs/returned [:int {:min 0}]]
 [:my.fs/examined [:int {:min 0}]]
 [:my.fs/complete? :boolean]]
```

The pattern uses JDK glob syntax, not a regular expression. Limits may lower,
never raise, cluster ceilings. Traversal is no-follow and stops as soon as the
traversal or result ceiling is reached. Paths are relative to `root`, sorted
lexically, and returned only after stat confirms their no-follow shape.
`complete? false` is the honest signal to narrow the root or pattern. There is
no fabricated total when traversal stopped early.

Errors: `:my.fs/path-refused`, `:my.fs/not-directory`,
`:my.fs/invalid-glob`, and `:my.fs/glob-failed`.

### `my.fs/stat`

```clojure
:my.fs/stat-request
[:map [:my.fs/path :my.fs/path]]

:my.fs/stat-result
[:map
 [:my.fs/path :my.fs/path]
 [:my.fs/regular-file? :boolean]
 [:my.fs/directory? :boolean]
 [:my.fs/symbolic-link? :boolean]
 [:my.fs/byte-size {:optional true} :my.fs/byte-count]
 [:my.fs/modified-at {:optional true} :inst]]
```

`stat` never follows the final link. A symbolic link inside an allowed root is
reported as a link but its target is neither resolved nor described. Missing
paths return `:my.fs/not-found`; they are not a success row with four false
flags. The result deliberately has attributes, not a `:type` or `:kind` field.

### Filesystem falsifiers

- Read a file containing NUL and invalid UTF-8 as bytes; byte count and digest
  must match an independent `MessageDigest` pass and no replacement character
  may appear.
- Read an interior UTF-8 byte window that splits a multibyte character; text
  mode must refuse while byte mode returns the exact octets.
- Modify a file between the read pass and final attribute sample; the handler
  must return `changed-during-read`, not mixed evidence.
- Attempt create and replace through final, intermediate, broken, and swapped
  symlinks. Every target outside the root must remain unchanged.
- Race two writes with one expected digest; exactly one may change the file and
  the other must report the new actual digest.
- Interrupt a staged write before the move. The old file remains byte-identical
  and cleanup cannot affect a symlinked sentinel.
- Glob a tree larger than both ceilings. The result must stop, remain bounded,
  and say `complete? false` without claiming a total.
- Return extra request keys. Declared keys still validate and unused keys are
  ignored, proving the maps remain open.

### What not to build in `my.fs`

- no list/walk/search variants beside bounded glob;
- no hidden current directory, home-directory helper, or env-derived root;
- no per-agent read/write grant, grant inspection, or mutable config atom;
- no unconditional overwrite, append, recursive delete, copy tree, move tree,
  chmod, or symlink creation in the first slice;
- no `readAllBytes`, lossy decoder fallback, character count presented as byte
  count, or silent truncation;
- no automatic Clojure syntax check inside raw `write`; `my.edit/form` owns
  structural source guarantees; and
- no direct babashka.fs or NIO binding in SCI.

## `my.edit`: source forms first, text fallbacks explicit

### Ownership and transaction

`my.edit` is a separate public namespace because safe editing is a higher-level
contract than filesystem mutation. `seon.edit` is a pure rewrite-clj core:
source bytes plus an edit request produce either candidate bytes plus evidence
or a flat error. `seon.edit.jvm` is the one capability handler. It performs a
single read–transform–conditional-write request so no second effect can race
between a public `read` and public `write`.

The handler uses the exact `my.fs` path law and atomic writer. Every edit
requires `:my.edit/expected-digest`, so current-read state is explicit and
durable. All successful operations return the common result:

```clojure
:my.edit/result
[:map
 [:my.edit/path :my.fs/path]
 [:my.edit/changed? :boolean]
 [:my.edit/before-digest :my.fs/digest]
 [:my.edit/after-digest :my.fs/digest]
 [:my.edit/before-bytes :my.fs/byte-count]
 [:my.edit/after-bytes :my.fs/byte-count]
 [:my.edit/from-line [:int {:min 1}]]
 [:my.edit/to-line [:int {:min 1}]]
 [:my.edit/context :string]
 [:my.edit/context-complete? :boolean]]
```

Context is a configured, byte-bounded UTF-8 window around the resulting form or
text span. The line coordinates describe evidence, not edit identity. If the
bounded context cannot include the whole changed span,
`:my.edit/context-complete?` is false. Large diffs are not returned inline; the
before/after digests are the stable evidence and ordinary Git tooling can
derive a repository diff.

### `my.edit/form`

```clojure
:my.edit.form/selector
[:map
 [:my.edit.form/head :symbol]
 [:my.edit.form/name :symbol]
 [:my.edit.form/dispatch-source {:optional true} [:string {:min 1}]]]

:my.edit/form-request
[:and
 [:map
  [:my.edit/path :my.fs/path]
  [:my.edit/expected-digest :my.fs/digest]
  [:my.edit/form :my.edit.form/selector]
  [:my.edit/operation [:enum :replace :insert-before :insert-after :delete]]
  [:my.edit/source {:optional true} :string]]
 [:fn my.edit/valid-form-operation?]]
```

`valid-form-operation?` requires exactly one replacement form for replace and
insert operations and requires `:my.edit/source` to be absent for delete. Extra
unrecognized keys remain accepted.

The pure algorithm is:

1. Decode the file as strict UTF-8 and parse the complete source with
   `rewrite-clj.zip/of-string*`; comments, whitespace, commas, reader forms,
   and line endings stay in the tree.
2. Visit top-level semantic forms only. Unwrap metadata, then compare the first
   and second semantic children as Clojure symbols with the selector's `head`
   and `name`.
3. When `dispatch-source` is supplied, parse it as exactly one node and compare
   it to the third semantic child as a form. Its source string is never split or
   normalized.
4. Refuse zero or multiple matches with bounded candidate head/name/line
   evidence. There is no nearest match.
5. Parse `source` as exactly one complete form. Replace, insert beside, or
   remove the selected node using rewrite-clj zipper operations.
6. Render with `root-string`, parse the entire candidate again, and verify that
   every byte outside the selected top-level node and necessary adjacent
   separator is unchanged.
7. Conditional-write the candidate using the expected digest.

The initial selector intentionally addresses named top-level forms. It covers
`ns`, `def`, `defonce`, `defn`, `defn-`, `defmacro`, `defmulti`, `defmethod`,
and `deftest` without maintaining an allowed-head list: the request supplies the
actual parsed head. Anonymous or deeply nested rewrites use a later explicit
structural selector only after real cases establish its identity contract.
They do not justify fuzzy tree search now.

Errors: `:my.edit/stale-source`, `:my.edit/not-utf8`,
`:my.edit/parse-refused`, `:my.edit/no-match`,
`:my.edit/ambiguous-match`, `:my.edit/invalid-replacement`,
`:my.edit/lossless-check-failed`, and the underlying atomic-write errors.

### `my.edit/exact`

```clojure
:my.edit/exact-request
[:map
 [:my.edit/path :my.fs/path]
 [:my.edit/expected-digest :my.fs/digest]
 [:my.edit/old-string [:string {:min 1}]]
 [:my.edit/new-string :string]
 [:my.edit/replace-all? {:optional true} :boolean]]
```

The file must be strict UTF-8. No newline, tab, Unicode, or indentation
normalization occurs. By default `old-string` must occur exactly once.
`replace-all? true` applies to every exact occurrence and returns the count in
error/result data; `false` never means “first match.” Zero and ambiguous
matches return line-position evidence without changing the file.

This is the ordinary fallback for JSON, YAML, Markdown, shell scripts, and
other text. It remains explicit even on a Clojure file: `form` never catches a
parse or match error and invokes `exact` on the caller's behalf.

### `my.edit/lines`

```clojure
:my.edit/lines-request
[:map
 [:my.edit/path :my.fs/path]
 [:my.edit/expected-digest :my.fs/digest]
 [:my.edit/from-line [:int {:min 1}]]
 [:my.edit/to-line [:int {:min 1}]]
 [:my.edit/old-window :string]
 [:my.edit/new-window :string]]
```

Lines are one-based and inclusive. The byte span begins at `from-line` and ends
immediately before `to-line + 1`, or at EOF. `old-window` must equal that exact
UTF-8 span, including its existing line terminators. `new-window` supplies its
own terminators. This preserves CRLF, a missing final newline, and blank lines
outside the range without inference. An invalid range or mismatch refuses the
edit and returns the actual bounded window/digest.

Line editing is a navigation fallback, not a line-number-only patch format.
Both the file digest and exact prior window fence it against drift.

### Editing falsifiers

- Replace a defn surrounded by comments, reader-discarded forms, commas,
  metadata, CRLF, and irregular whitespace. Every untouched byte must remain
  identical.
- Select two same-name `defmethod` forms without dispatch and prove ambiguity;
  then select one with parsed dispatch source.
- Supply malformed replacement source and malformed original source. Neither
  may invoke a text fallback or alter the file.
- Change the file after obtaining the digest. All three operations must return
  stale-source before matching.
- Give exact replacement zero, one, and several matches. Only one succeeds by
  default; explicit replace-all changes precisely the measured count.
- Replace a CRLF line window and a final line without a newline. Bytes outside
  the specified span must be identical.
- Reparse every successful form edit with rewrite-clj and the repository's
  Clojure reader, then allow the existing edit publication hook to report
  clj-kondo findings. The editor does not hide those findings or run another
  linter.
- Race two form edits at one digest. Exactly one conditional write may land.

### What not to build in `my.edit`

- no unified-diff parser as the primary execution contract;
- no fuzzy, whitespace-normalizing, indentation-correcting, or “near” match;
- no regular expression over source, constructed `render-<name>`-style
  selector, or form-head allowlist;
- no automatic paren repair, formatter pass, require sorting, or unrelated
  cleanup;
- no mutable undo history—Git and before/after digests are the history;
- no multi-file transaction claim; each file edit is one digest-fenced effect;
  and
- no silent fallback from form to exact or line editing.

## `my.shell`: one bounded foreground process

### Dependencies and process semantics

`my.shell/run` depends on four settled owners:

1. `seon.effect` for receipt-before-dispatch and interruption;
2. `my.fs` policy for an honest cwd and artifact paths;
3. a binary `seon.blob` sink for complete large output; and
4. `babashka.process/process` plus `destroy-tree`, not `shell`, pipelines, or
   `check`.

Before `my.shell` implementation, extend `seon.blob` with a binary streaming
owner. A capture sink retains at most the configured inline prefix in memory.
When that threshold is crossed, it creates one explicit staging file beneath
the process root, writes the retained prefix, then streams remaining chunks
while updating SHA-256 and byte count. At EOF it calls Konserve `bassoc` with
the staged `File` under the final digest, verifies the stored bytes, and removes
only that staging path. The settled effect receipt carries digest and size.
No partial output fact is committed, and a crash leaves at most an unreferenced
staging file for process-root cleanup.

This is an extension of `seon.blob`, not a shell-specific artifact store.

### Config facts

The initial shell config declares:

```clojure
:seon.config.shell/time-limit-ms       [:int {:min 1 :seon.config/dial true}]
:seon.config.shell/termination-grace-ms [:int {:min 1 :seon.config/dial true}]
:seon.config.shell/inline-output-bytes [:int {:min 1 :seon.config/dial true}]
:seon.config.shell/preview-bytes       [:int {:min 1 :seon.config/dial true}]
:seon.config.shell/stdin-max-bytes     [:int {:min 1 :seon.config/dial true}]
:seon.config.shell/path
[:string {:min 1
          :seon.config/dial true
          :seon.shell/environment "PATH"}]
:seon.config.shell/home
[:string {:min 1
          :seon.config/dial true
          :seon.shell/environment "HOME"}]
:seon.config.shell/lang
[:string {:min 1
          :seon.config/dial true
          :seon.config/optional true
          :seon.shell/environment "LANG"}]
```

Defaults require measurements before shipment. `time-limit-ms` is legitimate:
a foreign process can stop producing observable events forever. Completion is
still event-driven through process exit; the clock is only the external-state
backstop. `termination-grace-ms` bounds the genuinely foreign interval between
polite tree termination and forced tree termination.

The child process INHERITS THE COMPLETE PARENT ENVIRONMENT (owner ruling
2026-08-03, applying the no-hobbling ruling: agents are trusted
collaborators, we cannot know which value is a credential, and nothing is
blocked or filtered on that basis). The config attributes above are OPTIONAL
OVERRIDES layered on that inheritance, not a projection allowlist: a declared
`:seon.shell/environment` property names the variable an override sets, and
adding a later override is another declared config attribute — never a
variable list in shell code. This section previously specified a sanitized
child environment and was REWRITTEN under the ruling; no environment
sanitization, credential filtering, or undeclared-variable blocking exists
anywhere in `my.shell`.

### Request and result schemas

```clojure
:my.shell/argv
[:vector {:min 1} [:string {:min 1}]]

:my.shell/stdin
[:and
 [:map
  [:my.shell/stdin-text {:optional true} :string]
  [:my.shell/stdin-bytes {:optional true} :my.fs/bytes]
  [:seon.blob/digest {:optional true} :seon.blob/digest]]
 [:fn my.shell/stdin?]]

(defn stdin?
  [value]
  (= 1 (count (filter #(contains? value %)
                      [:my.shell/stdin-text
                       :my.shell/stdin-bytes
                       :seon.blob/digest]))))

:my.shell/run-request
[:map
 [:my.shell/argv :my.shell/argv]
 [:my.shell/cwd :my.fs/path]
 [:my.shell/stdin {:optional true} :my.shell/stdin]]

:my.shell/output
[:and
 [:map
  [:my.shell.output/bytes :my.fs/byte-count]
  [:my.shell.output/digest :seon.blob/digest]
  [:my.shell.output/text {:optional true} :string]
  [:my.shell.output/octet-values {:optional true} :my.fs/bytes]
  [:my.shell.output/blob {:optional true} :seon.blob/digest]
  [:my.shell.output/preview {:optional true} :string]
  [:my.shell.output/preview-complete? :boolean]]
 [:fn my.shell/output?]]

(defn output?
  [value]
  (= 1 (count (filter #(contains? value %)
                      [:my.shell.output/text
                       :my.shell.output/octet-values
                       :my.shell.output/blob]))))

:my.shell/run-result
[:map
 [:my.shell/argv :my.shell/argv]
 [:my.shell/cwd :my.fs/path]
 [:my.shell/exit :int]
 [:my.shell/stdout :my.shell/output]
 [:my.shell/stderr :my.shell/output]]
```

The argv vector reaches `babashka.process/process` without tokenization or a
shell. `cwd` is required, no-follow, and inside filesystem policy. Stdin is
checked against the configured byte ceiling and streamed; blob stdin is
verified while copied.

Stdout and stderr are drained concurrently from their separate streams on
virtual threads. Each descriptor always reports the complete stream's byte
count and SHA-256. Valid UTF-8 at or below the inline ceiling returns `text`.
Small binary output returns `octet-values`. Output above the inline ceiling, or
binary output above the admitted ordinary-value size, returns `blob` plus a
bounded UTF-8 preview when one can be decoded. `preview-complete? false` means
only the preview is partial; the blob is complete. Nothing uses the word
“truncated” for a result whose complete bytes exist by digest.

Nonzero exit is a successful process result, not a tool error. Start failure,
cwd refusal, stdin failure, capture failure, and the time limit are errors. A
time-limit error includes the post-termination stdout/stderr descriptors in
`:seon.error/data` so evidence is not discarded.

The execution sequence is:

1. validate argv/cwd/stdin and open the effect receipt;
2. call `babashka.process/process` with explicit streams, cwd, and sanitized
   environment;
3. immediately record the exact child `(pid, start-instant)` on the open
   receipt, then start the two capture sinks and stdin copier;
4. observe `Process.onExit`; independently arm the config time limit;
5. on time limit, call `babashka.process/destroy-tree`, wait only the configured
   grace, then force the exact still-matching process tree;
6. await EOF from both captures, settle blobs, and return exit/output; and
7. settle the effect receipt once.

There is an honest crash interval between OS process creation and recording its
identity. A receipt left open in that interval means the command may have run;
recovery marks it interrupted and never refires. Once identity is present,
restart reconciliation may terminate the exact still-live child tree before
marking interrupted. The first live proof must terminate the cluster JVM at
both sides of that identity commit and record the observed descendant state.

Errors: `:my.shell/cwd-refused`, `:my.shell/stdin-limit`,
`:my.shell/blob-unavailable`, `:my.shell/start-failed`,
`:my.shell/time-limit`, `:my.shell/capture-failed`, and
`:my.shell/interrupted`.

### Shell falsifiers

- Run argv entries containing spaces, quotes, wildcard characters, dollar
  signs, and command separators; the child must receive the exact vector and
  no shell expansion may occur.
- Emit interleaved stdout/stderr larger than pipe buffers. Both streams must
  drain without deadlock and independently match external byte counts/digests.
- Emit invalid UTF-8 and NUL bytes above the inline threshold. The complete
  blob must round-trip byte-for-byte; preview decoding may omit text but cannot
  replace bytes.
- Exit nonzero with stderr. The result must retain the exit and output rather
  than throw or become `start-failed`.
- Block forever, spawn descendants, and ignore polite termination. The config
  limit must trigger tree cleanup, preserve captured bytes, and return a flat
  time-limit error.
- Terminate the JVM immediately before and after child identity commit. The
  receipt must remain explainable, no command may refire, and any exactly
  recorded survivor must be reconciled.
- Produce output just below and just above the inline threshold. The logical
  digest and bytes must be identical across inline and blob representations.
- Run an env dump and prove the child received the complete parent
  environment plus any declared overrides, with each override's declared
  value winning over the inherited one.

### What not to build in `my.shell`

- no shell string, tokenizer, implicit pipeline, redirection syntax, glob
  expansion, or `bash -c` convenience;
- no command allowlist masquerading as callability and no per-agent grant;
- no environment sanitization, credential filter, or variable allowlist —
  the child inherits the complete parent environment (owner ruling
  2026-08-03);
- no `ByteArrayOutputStream` proportional to child output and no discarded
  suffix after a cap;
- no background run, job atom, list/status/output polling API, or process-local
  job identity in this slice;
- no automatic retry after start, timeout, interruption, or process loss; and
- no second output/artifact store beside `seon.blob`.

## Implementation order and integrated proof

The dependency spine is:

1. declare/index `:seon.effect/capability`, separate effect reachability from
   workload reachability, and prove the queries;
2. implement the one `seon.effect/request!` receipt and handler-resolution
   path;
3. extend `seon.blob` to bounded binary reads and staged streaming writes;
4. vendor/pin babashka.fs and move the existing rewrite-clj pin to the selected
   production version;
5. implement `my.fs` path policy and four functions;
6. implement pure `seon.edit` transforms, then the single `my.edit` handler;
7. vendor/pin babashka.process and implement foreground `my.shell/run`; and
8. run the integrated live proof in a fresh isolated cluster.

The proof episode must:

- query the database to show the exact capability owner and handler facts;
- glob and bounded-read a Clojure file;
- structurally replace one form while preserving unrelated bytes;
- exact-edit one non-Clojure file under a digest fence;
- run the focused test through argv execution with output forced above the blob
  threshold;
- read that output back by digest and verify its bytes;
- interrupt a second process and explain its effect receipt after restart; and
- show that every observed filesystem, blob, and process operation has exactly
  one effect request identity and no unmarked direct host call.

Graduation is not a namespace count. It is the conjunction of query-derived
effect membership, bounded byte evidence, lossless Clojure editing,
conditional atomic mutation, complete process output, crash explanation, and
the absence of a second dispatch or registry mechanism.
