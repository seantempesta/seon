---
type: research
status: active; resumed after owner clarified item-local stop boundary
created: 2026-09-16
tags: [workarounds, program, environment, values-carry-their-world]
---

# Environment carries it — review boundary

## Resumed assignment: item 21

The owner clarified that item 20's stop applies only to that item. The
initial stop record below is historical; independent work resumed.

`widening-inputs` is removed. `widening-path?` is the complement of
`graph-roots`, with exact path-segment matching. Input hashing enumerates
Git's tracked and non-ignored files, using the snapshot's own working-tree
bytes. Git supplies NUL-delimited paths; no filename/prose parsing or input
roster remains. Directory links and submodule directories are not walked.
The existing Babashka process owner supplies bounded completion and cleanup
(`reference-code/babashka-process/src/babashka/process.cljc:119`, `:165`).

At edit time selection.clj was clean; its existing `read-basis` region was
unchanged. No foreign hunk was included. Verification:
`bin/test-fast --paths src/seon/test/selection.clj
test/seon/test/selection_test.clj -- seon.test.selection-test`: **5 tests,
27 assertions, 0 failures, 0 errors**. Log:
`tmp/environment-selection-fast.log`. The regression covers new outside
paths, near-prefix paths and every declared graph root; existing content
and symlink regressions pass. A subsequent cleanup removed the now-unused
private symlink predicate and corrected the docstring; behavior is unchanged.

Read AGENTS.md §0–§3 and §5–§7 and
[workaround-inventory-2026-09-16.md](workaround-inventory-2026-09-16.md)
end to end. Assignment covers ranked items 20, 21, 24 and 30.

The assignment explicitly says for item 20: “program.cljc may be held by
the platform-tier agent — check; hunk + stop if so.” At HEAD
`0dec68dc43530b8087d4b93c1790c62f6f374905`, `git status --short --
src/seon/program.cljc` reports ` M src/seon/program.cljc`. Its existing
diff changes `test-marker-attributes`, adding `:seon.test/platform` and
`:seon.test/fixture` and their documentation. Those edits are preserved.
This is the explicit ownership stop, not a failed gate.

No production changes were made. Items 21, 24 and 30 remain unimplemented;
none of the four requested regressions has run. No `bin/test` or
`bin/test-fast` invocation was made. No cluster was started or restarted.

## Observed system and dependency ledger

- `bin/seon status`: default alive, PID 41413, prepl 51534, no orphan JVMs.
- MCP runtime status answered and reported existing problems: 95 failed
  tests, 11 errored evaluations, 2 error signatures and 2 stale Vars.
  These are inherited observations, not causes attributed to this work.
- Read-only MCP JVM evaluation of `(select-keys (seon.program/shape
  :seon.fn/sym) [:seon.program/identity-attribute
  :seon.program/owned-attributes])` returned the function identity and
  owned attributes in 42 ms. It exercised the currently loaded owner,
  not a changed definition or a fresh adoption.
- `src/seon/program.cljc` already derives row shapes from the identity
  attribute's `:seon.program/row-schema` link in `derived-shape`.
  `seon.schema.form/map-entries` strips the map head/properties; the
  existing `entry-properties` reads each entry's Malli properties.
  Required entries are those without `:optional true`.
- `seon.program/shapes-in` accepts an explicit declaration population.
  `canonical-row` already accepts its derived shape value. The proposed
  change uses that same value for canonicalization and required-key checks.

## Item 20: exact proposed hunks, not applied or tested

```diff
--- a/src/seon/program.cljc
+++ b/src/seon/program.cljc
@@
     {:seon.program/identity-attribute identity-attribute
      :seon.program/source-attribute source-attribute
+     :seon.program/required-attributes
+     (into []
+           (comp (remove #(true? (:optional (entry-properties %))))
+                 (keep entry-attribute))
+           entries)
      :seon.program/owned-attributes
@@
-(def ^:private declaration-required-attributes
-  {:seon.ns/name [:seon.ns/source]
-   :seon.fn/sym [:seon.fn/ns :seon.fn/source :seon.fn/arglists
-                 :seon.fn/private?]
-   :seon.schema/key [:seon.schema/form]
-   :seon.test/sym [:seon.test/ns :seon.test/source]})
-
 (defn declaration-row
@@
-  (let [event (assoc event :seon.schema.admission/source admission-source)
+  (let [row-shapes (shapes)
+        event (assoc event :seon.schema.admission/source admission-source)
@@
-        row (canonical-row candidate)]
+        row (canonical-row row-shapes candidate)]
@@
-                  (get declaration-required-attributes identity-attribute))]
+                  (:seon.program/required-attributes
+                   (shape row-shapes identity-attribute)))]
--- a/resources/seon/schemas/seon.program.edn
+++ b/resources/seon/schemas/seon.program.edn
@@
  :seon.program/owned-attributes
  [:or [:vector :qualified-keyword] :seon.program/schema-row-properties]
+ :seon.program/required-attributes
+ [:vector :qualified-keyword]
@@
   [:seon.program/source-attribute :seon.program/source-attribute]
+  [:seon.program/required-attributes :seon.program/required-attributes]
   [:seon.program/owned-attributes :seon.program/owned-attributes]]
```

The row-schema declaration becomes authoritative, including where it
disagrees with the deleted literal: for example `:seon.ns/source` is
currently optional in `resources/seon/schemas/seon.ns.edn`. Preserving
the literal's stronger requirement would require changing that declaration,
not retaining a second requirement list. The hunks must land together.

Required follow-through after release: add one regression using the
canonical population, change one optional row entry to required in the
supplied forms, and prove the derived required attributes change without
changing program code. Exercise declaration refusal under armed contracts;
check existing row-shape fixtures for the newly required shape member.
The supplied-population declaration path should carry its derived shapes
through the declaration operation, rather than resolving them per row.

## Remaining traced boundaries

Item 30: `seon.search/open!` returns a string index ID after putting the
live owner in `owners`; `apply-report!` and `close!` recover it by ID;
`search` recovers it through `db/*conn*`. The owner already contains the
Lucene `SearcherManager` and acquires/releases readers in `search-owner`.
Removing the registry requires carrying that actual owner through boot,
environment, flow and search requests together. The existing
`:seon.search/index` schema is a tokenizer enum, so it cannot be repurposed
as the handle schema. `cluster.clj` was dirty on entry but clean at the
final ownership check; it is not claimed protected at this stop.

Item 21: `widening-inputs` also feeds `input-digests`. Deleting only the
predicate's list leaves its input enumeration unresolved. The predicate
must use the complement of `graph-roots`; enumeration also needs an
authoritative input source that works in the gate's copied checkout.

Item 24: the script currently makes a configuration call before the work
call. Both protocol construction and default-bound acquisition need to move
to the JVM owner for the requested single-call result. The successful
result must require pass/fail/error counts; typed failure must remain
distinct from a legitimate zero-pass result.

Only this landing note is committed. Stop for owner review as instructed.
