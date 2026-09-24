# Lane fix-mcp-output (2026-09-24)

The MCP bridge (`script/seon/dev/mcp.clj`) reads each prepl `:ret` and re-encodes it as JSON. This lane changes
that output only: the value now crosses as printed EDN, the caller's source text crosses unchanged, and a branch
reply returns just its value.

## Still broken or unverified

- **The default JVM cannot run the changed test file.** It runs from the committed snapshot
  `data/source/ce73846…`, so `bin/test-check default --ns seon.dev.mcp-bridge-test` still runs the old test body
  (144 pass / 3 fail / 7 error, the same before and after this edit). `--gate --changed-path` refuses because the
  changed paths "lie outside the JVM's source directory". The proof below comes from running the working-tree file
  in default under scratch namespace names. The installed request stays red until root moves default to this
  commit with `start --head`. RESET NEEDED: no; a head move is enough.
- **Symbols print as symbols now. Vars, fns and host objects still print as maps.** The
  `#:seon.sci.admit{:opaque …}` and `{:seon.sci.admit/name … :reference sci.lang.Var}` maps come from the cluster's
  projection (`seon.cluster/mcp-valf` → `admit/canonical-edn`), not from the bridge. That code is outside this lane.
- **SCI-mode values are harder to read.** The whole SCI record is now one EDN string, and its shown text
  (`:seon.dev.mcp/text`) sits inside that string, so its quotes are escaped twice. This is correct, but hard to read.
- **`get_value` still turns keywords into strings.** It is the same class of defect but was not in this lane's scope.
- **The first SCI `defn` on a fresh branch context cost 958 ms and allocated 1.76 GB.** It came from context
  acquisition (see research d5843c323), not from the bridge.
- **The issue file stays open.** `docs/seon/issues/mcp-jvm-eval-drops-reader-metadata-on-a-defn-name.md` is not in
  this lane's paths. Its success test now passes live in both modes; close it once root ships this.

## Defects (each checked live before the fix)

1. **`branch` echoed its generated form.** Every reply carried the ~3 KB macro-expanded form. The reply is now
   `{root cluster action :seon.dev.mcp/value <edn>}`. The fix also exposed a second bug: the refusal check read
   `[:val :seon.dev.mcp/value :seon.error/at]` from a `:val` that was never decoded, so every refusal was reported as
   success. The value is now read before the check, and a refusal returns `isError` (verified live by creating an
   existing branch). Events are attached only when the prepl reports an exception.
2. **`eval_clj` turned keywords into strings.** `:seon.dev.mcp/value` is now the value's `pr-str`, printed with no
   print length or level limit. This happens after tail-elision enrichment. Live: `{:k :seon.note.task/drop-note-row, :n 1, :s foo/bar}`.
3. **Metadata on a `defn` name was dropped.** Cause, verified: `jvm-evaluation-form` re-printed the read form with
   `pr-str`, which drops metadata unless `*print-meta*` is bound. Now the caller's source goes to the JVM and
   `read-string` (`:read-cond :allow`) reads it there, after `in-ns`. A side effect is that `::kw` now resolves in
   the target namespace (live: `:scratch.probe-meta/here`). Live: `:name-meta` now has `:malli/schema` and
   `:probe/x`. SCI mode already sent source text and keeps both keys.
   **The namespace mislabel** reproduced in SCI mode only: the event's `:ns` named the host prepl's `*ns*`
   (`scratch.task-context`) instead of the SCI namespace. SCI events no longer carry `:ns`; the response's
   `:seon.dev.mcp/namespace` names the namespace once.
4. **Test assumptions retired in 076827cc9.** That commit (B1b) had already removed several things these tests still
   used: `operator-private`, `cluster-layer-form`, `jvm-snapshot-form` and `responsive-advertisement?`. The test
   also expected the tool list without `branch` and old description text. The tests now write real
   `data/clusters/<dir>/prepl.edn` files for this process instead of redefining Vars. One test was deleted:
   endpoint-selection-via-JVM-registrations, because that mechanism is gone. The discovery and census tests were
   rewritten to cover what discovery does now.

The class regression is `results-carry-the-printed-value-and-the-authored-form`. It checks three things: the printed
EDN value, that name metadata reaches the JVM, and that a branch reply carries no form or events.

## Evidence

- Test file, working tree, run in default under scratch names (`scratch.fix-mcp-output.{bridge,test}`, removed
  afterwards): 26 tests, 164 pass, 0 fail, 0 error, 1241 ms.
- Response sizes, measured by babashka calling `execute-tool` on HEAD's and then the working tree's bridge against
  live default:

  | probe | before | after |
  |---|---|---|
  | `branch list` | 3702 B | 440 B |
  | `eval_clj {:k :kw :s 'sym :n 1}` | 485 B | 483 B |
  | `eval_clj (+ 1 2)`, including discovery (1 sample) | 75 ms | 83 ms |

  The eval time difference is 8 ms, from one sample, under the 50 ms regression threshold.
- Live checks through the auto-reloaded MCP tools, after the edit: branch list, create, refusal and retire; JVM
  keyword/metadata probe; SCI probe on branch `fix-mcp-output`, which is now retired.
- clj-kondo reports no new warnings.

## Size

`script/seon/dev/mcp.clj` +25/−7. `test/seon/dev/mcp_bridge_test.clj` +102/−190.
