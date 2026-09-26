---
type: report
status: committed, not adopted
created: 2026-09-26
lane: fix-floor-review-opus
review: review-floor-selection-continuation-diff-2026-09-26.md (Astra REVISE, F1–F4)
commit: fe09342a7
---

# Floor review F1–F4: landing

Commit `fe09342a7` fixes all four blocking findings at their producers. The four
issues were: HTML used a second, unbounded selection; a map key's cut named a
different value; MCP's first page and its continuation used different backings;
and a page could fail to show its root or first member.

Default runs the committed archive, so nothing here is installed. Every "after"
number below is from the committed source loaded into a scratch namespace
(unarmed) on default PID 13262.

## Broken or open first

1. **F3 is only half landed.** The cluster.clj half is the patch
   `tmp/fix-floor-review-opus/cluster-F3.patch`. That file belongs to
   inv-lifecycle-storage-opus. The patch applies cleanly to HEAD with
   `git apply --check`. Until root applies it:
   - MCP still selects the held value;
   - `get_value` still has its own read, navigate and select body.

   The test half is `tmp/fix-floor-review-opus/mcp_test-F3.patch`. It
   changes one assertion: an unknown digest now answers the one owner's
   `:gone` refusal instead of `:seon.dev.mcp/value-not-found`.
2. **Humans see less HTML than before.** This is the F1 fix, and it follows
   the design, which supersedes the 2026-09-07 "HTML unbounded" ruling. HTML
   now shows the same selection as the AI text: B = 15,000 tokens by default.
   - Cuts are links (`:seon.print/href`) only where the unit has a
     `:seon.render.value/route-base`, which today means `/data` pages.
   - On agent and debug pages, a cut is plain `…N` text with no expander.
     Before this change those pages rendered the whole value.
   - Expanders there, and the realization offer, are design row 2(iii), which
     is not built.
   - A long keyword or symbol cut is shown through the name's own text, so it
     carries no link.
3. **The MCP `:seon.dev.mcp/value` is still the tree converted back to data**
   (`admit/semantic-value` of the selected node), with the elision maps
   inline. It is not yet the shared AI text that design §3h item 9 asks for.
   This lives in cluster.clj and was not touched.
4. **A stored backing loses a sorted map's order.** An artifact reads a sorted
   map back as a hash map, so a stored MCP value shows it in hash order. It
   is consistent now (F3), but less readable. The design's stored selection
   tree would keep the order. That tree is not built here.
5. **Some continuations still cannot navigate from the raw value.** This is an
   open class, older than this change. It affects paths under three kinds of
   node, whose selection shows a derived value:
   - a declared identity projection;
   - a reference replaced by its referent's lookup ref (`reference-value`);
   - a Throwable (`throwable-value`).

   A cut under one of these nodes names a path in the derived value, so `at`
   over the raw value refuses it with `no-such-path`.
6. **Two foreign readers do not route the new refusal.** `sci/eval.clj:3770`
   (`shown-result`) and `repl.clj:483` test only
   `:seon.render.value/root-description`.
   - If the insufficient-budget refusal reached them, `render-ai-data`'s
     contract would refuse it loudly.
   - It can only happen with a profile far below the default: a first member
     is at most about 2 × 32 characters plus the legend (about 117).
   - The one-line fix is to test `:seon.error/at`. It is root's to apply or
     assign.
7. **One foreign test asserts the retired unbounded HTML.**
   `test/seon/instrument_test.clj:724` asserts that "the HTML face keeps
   every member of the whole offending value". It will be red, and it needs
   the one-selection assertion. The file is not in this lane's row.
8. **Not run:** no `bin/test-check` request ran, because default does not
   carry these commits. Everything under "Root runs" is owed.

## Each finding

### F1: HTML disabled the selector's bounds

- **Reproduced** at 20 tokens on `(vec (range 3000))`: AI showed a cut, and
  HTML had all 3,000 leaves (51.6 ms).
- **Cause:** `prepare` built two contexts. The HTML one set `:charge? false`
  and `Long/MAX_VALUE` for budget, children, depth and string, plus a
  `realize-cap` (the old `value.clj:965–991`). So the two faces came from two
  different selections.
- **Fix:** one context and one tree, and `print/emit-both` draws both faces
  from it. Deleted: `:charge?`, the `ai?` branches, `realize-cap`,
  `settle-in`, and the unused `config-defaults` read for it.
- **HTML cuts are expanders:**
  - `cut-node` adds `:seon.print/href`, built by the previously unused
    `path-url` from the cut's path and next offset.
  - `print/node-ops` emits it as an `<a>` in the hiccup; the text face is
    unchanged.
  - `:seon.print/href` is declared in `seon.print.edn`.
- **After:** the HTML tree equals the AI tree (`=` on the tree). There are 25
  leaves at 60 tokens, and the cut's href carries its offset.

### F2: map-key coordinates identified a different value

- **Reproduced:** in `(array-map (vec (range 50)) :x 0 :zero)` at 60 tokens,
  the key's cut had path `[0]` and next-offset 23. `(render.data/at v [0])`
  returned `:zero`: the value of key `0`, not the key vector.
- **Cause:** `admit-member` gave the key node the entry ordinal as a plain
  step. It gave the value node the raw key, and a set member the member
  itself. `at` reads a plain int on a map as a key lookup.
- **Fix, one step grammar:**
  - A key role is `[:seon.render.data/entry i :seon.render.data/key]`.
  - A non-keyword key's value, and any tree-map value, is
    `[... :seon.render.data/val]`.
  - A set member is `[:seon.render.data/nth i]`.
  - A keyword key in an exact array map or hash map stays a plain keyword,
    and a sequence position stays a plain int. These keep `get-in`'s meaning,
    because the floor never emits a plain int for a map.
  - `i` is the member's index in `seq`. `held-order` now returns
    `[index member]`, so the sorted display of a small all-keyword hash map
    still addresses the held value.
- **One navigator:** `seon.print/path-step`. `render.data/at` and
  `print/value-at` now both reduce with it. Their two separate bodies are
  deleted: `at`'s `contains?`/`nth`/`set` cond and `value-at`'s `get`/`nth`
  reducer.
- **After:** the key cut's path is `[[:seon.render.data/entry 0
  :seon.render.data/key]]`, it navigates to the 50-element vector, and the
  continuation at offset 23 shows `[23 24 …`, whose next offset is 45.
- **Legend:** it now says `(seon.print/value-at result/eN [...])` instead of
  `get-in`, because typed steps are not `get-in` steps.

### F3: the first page and its continuation had different backings

- **Reproduced:** with `{:t (into (sorted-map) …100 entries)}`, first
  selecting the held value and then navigating the stored artifact (as
  `get_value` does) gave:
  - first page `0 … 30`;
  - continuation `31, 32, 40, 91, 56, 33 …`, in the artifact's hash order,
    which skips and repeats members.

  An artifact round trip keeps the order of hash maps and hash sets, but not
  of sorted maps.
- **Cause:** `97f4d1f90`. `mcp-project` selected the held value, while
  `mcp-get-value` rebuilt the artifact value and navigated that, as did
  `/data?value=` through its own blob read.
- **Fix:**
  - `walk/continue` resolves a `[:seon.blob/digest d]` designator through the
    caller's `:seon.db/connection`, which is declared on
    `continue-request`. Missing blob with a connection → `:gone`; no
    connection → `:unavailable`; unreadable content → `:malformed`.
  - `/data?value=` is an adapter over `continue`. Its own read, refusal and
    fault body is deleted: `web.clj` +18 / −39.
  - The patch makes `mcp-project` select `artifact-value` whenever it stores
    the artifact, which is the backing `get_value` continues. Unstored values
    still select the held value, and their cuts already refuse continuation
    by name.
  - The patch also makes `mcp-get-value` an adapter over `continue`
    (+34 / −51).
- **After** (both pages from the backing): the first page ends at `21`, and
  the continuation starts `31, 32, 40, …`, the next members in the same
  order.

### F4: an unaffordable root was emitted, and a page could make no progress

- **Reproduced** at 1 token (B = 3):
  - `"abcdefghijklmnop"` emitted 18 characters;
  - at 20 tokens (B = 64), `{:a 1 :b 2}` emitted `{…2}` plus the legend,
    117 characters, with next-offset 0. Continuing it repeats the same page.
- **Cause:** `select-nodes` installed the root without charging it. The final
  check compared the text with the charge, never the charge with B, and it
  never checked that the first member was admitted.
- **Fix:** `select-nodes` returns the new declared
  `:seon.render.value/insufficient-floor-budget-error` in two cases:
  - the root's charge exceeds B (the legend counts only when the root has a
    cut);
  - the root's first member was refused for budget. `admit-member` records
    that charge as `:first-refused`.

  The error carries `:seon.render.value/budget` and
  `:seon.render.value/needed`, with the path in `:seon.error/data`. It is
  declared in `seon.render.value.edn`, over `:seon.error/base`. `prepare`,
  `render-ai`, `render-html` and `transcript/history-value-text` declare it,
  and `render-prepared` passes any `:seon.error/at` value through.

  Since both faces are always charged, every admission now checks
  `charge ≤ B`, so the charge never exceeds B.
- **After:**
  - the 16-character root → refusal `{budget 3, needed 18}`;
  - `{:a 1 :b 2}` at B = 64 → refusal `{budget 64, needed 117}`;
  - `42` at B = 3 → `"42"`, since a root that fits whole needs no legend.
- The design's refusal was kept. The minimum profile was not raised, and there
  is no second clipping pass.

## Measured (scratch, unarmed, default PID 13262)

The before column is HEAD's `value.clj` and the after column is
`fe09342a7`'s, both in scratch namespaces. Times are ms for one warm run
after two.

| value | tokens | AI before → after | HTML before → after | HTML characters before → after |
|---|---|---|---|---|
| three-branch probe | 128 | 0.8 → 0.7 | 0.7 → 2.8 | 1,675 → 385 |
| root agent row | 150 | 1.2 → 1.5 | 9.1 → 2.4 | 9,519 → 478 |
| root agent row | 15,000 | 10.8 → 12.9 | 7.1 → 12.3 | 9,519 → 9,519 |
| projection forms (3,435 keys) | 15,000 | 7.4 → 5.9 | **358.8 → 4.8** | 707,767 → 6,010 |
| `{:a (vec (range 100000)) …}` | 150 | 0.5 → 0.7 | **627.8 → 0.5** | 624,644 → 240 |

- The AI text is byte-identical in length for every case.
- The agent row at 15,000 tokens is +2.1 ms (19%). That is single-run noise
  territory and inside the 20% / 50 ms rule, but it needs an armed rerun.
- The HTML regressions at small sizes are the charge now being applied.
- Other checks:
  - `seon.contracts-compile-test/check` over the five touched sources, the
    patched cluster.clj and value_test gave `[]` in 579 ms;
  - the three touched schema files read as EDN;
  - the real refusal, projection and href-bearing tree validate against
    `:seon.render.value/insufficient-floor-budget-error`,
    `:seon.render.value/projection` and `:seon.print/node` in the checkout's
    packaged projection.
- Armed cost, allocation and browser paint were not measured.

## Changed paths and size

Source, counting only this lane's files:

| file | + | − |
|---|---|---|
| `value.clj` | 118 | 77 |
| `print.cljc` | 37 | 11 |
| `data.clj` | 6 | 17 |
| `walk.clj` | 14 | 2 |
| `web.clj` | 18 | 39 |
| `transcript.clj` | 1 | 1 |
| **source total** | **194** | **147** (net +47) |
| schemas | 9 | 1 |
| tests | 112 | 35 |
| cluster.clj patch | 34 | 51 (net −17) |

With the patch, the source is net +30. Most of the additions are docstrings
and comments.

Deleted: the HTML context branches, `settle-in`, `realize-cap`, the two
navigator bodies, `/data`'s blob read and fault path, and (with the patch)
`get_value`'s navigate and window body.

Released: none; the row stays held until root adopts.

**Tests:**
- New class regressions in `test/seon/render/value_test.clj`:
  - `one-selection-bounds-both-faces-and-each-cut-expands-in-place` (F1);
  - `a-composite-key-cut-addresses-the-key-it-was-cut-from` (F2: key, set
    member, and a plain step keeping `get-in`'s meaning);
  - `an-unaffordable-root-or-first-member-is-the-declared-refusal` (F4);
  - `a-stored-value-continues-over-the-backing-its-first-page-selected` (F3,
    through `walk/continue` with a real blob and connection, plus the
    `:unavailable` case).
- Converted to retire the unbounded-HTML assumption: five value tests and two
  web tests.

## Root runs

Adopt `fe09342a7`, then apply and commit the two patches:

```
git apply tmp/fix-floor-review-opus/cluster-F3.patch
git apply tmp/fix-floor-review-opus/mcp_test-F3.patch
```

Then run:

```
bin/test-check default --test seon.contracts-compile-test/every-declared-contract-compiles-against-the-packaged-projection
bin/test-check default --test seon.schema.edn-test/every-packaged-schema-resource-reads-as-edn
bin/test-check default --ns seon.render.value-test
bin/test-check default --ns seon.render.data-test
bin/test-check default --ns seon.print-test
bin/test-check default --ns seon.render.walk-test
bin/test-check default --ns seon.render.web-test
bin/test-check default --ns seon.cluster.mcp-test
bin/test-check default --ns seon.instrument-test      # :724 expected red (item 7)
bin/test-check default --ns seon.render.transcript-test
bin/test-check default --ns seon.problems-test
bin/test-check default --ns seon.render-source-test
```

After that:
- Astra reviews this diff and the two patches (CLAUDE.md §4).
- Rerun the table above armed.
- Open `/data?value=<digest>` in the browser and click a cut: it should load
  the page at that offset.

## Verification limits

- The probes were read-only JVM calls on scratch copies. These were loaded
  into default:
  - `tmp.fixfloor.step`, `tmp.fixfloor.value` and `tmp.fixfloor.before`,
    since removed;
  - `seon.contracts-compile-test`, which was required so its `check` could
    run and is still loaded.
- No MCP SCI branch was used, because a branch runs default's installed code,
  which does not carry these commits.
- `value.clj`'s `schema.edn/load!` ran once under the scratch namespace name.
