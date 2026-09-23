---
type: research
status: current
created: 2026-09-23
scope: Clojure-native mechanisms for "data tells its own story" — renderer selection with inheritance and override, and navigation from a value to its related context (datafy/nav, Clerk, Portal, Reveal, Morse; Seon's installed render selection).
---

# Data tells its own story: selection, inheritance and nav

Read-only research under the implementation freeze
(`tmp/orchestrator/data-story-brief.txt`). Every claim is marked VERIFIED
(read or ran) or UNVERIFIED. Pinned revisions: `reference-code/clojure`
`b18d3adc`, `reference-code/datahike` `c79cd03a`, `reference-code/sci` `fcbd8862`;
`reference-code/malli` gitlink `8725a8cb` (the working checkout is
`56394c54`, a foreign uncommitted submodule move; only a grep is cited); Clerk `main` at `4c259994`
(downloaded, not vendored). REPL probes ran on `default` (pid 48902) in
JVM mode, read-only, `let`-scoped, no defs.

## 1. Answer first

1. **Seon already has the universal rule; it is buried under four other
   stages.** `seon.render/selection` walks
   `[:explicit-value :explicit-request :namespace :schema :floor]`
   (`src/seon/render.clj:453-454`, loop `:580-606`). The `:schema` stage
   does most of what the owner describes: it finds every schema the value
   satisfies (`seon.schema/matching-shapes-in`, `src/seon/schema.clj:4163-4180`),
   keeps only schemas that declare the requested output property, and picks
   the **most specific** one: the most required or present attributes
   (`src/seon/render.clj:334-380`). Several distinct producers at the same
   specificity give one flat ambiguity error (`:382-389`, `:310-326`). That
   rule is inheritance by satisfaction: a broad schema's renderer applies to
   every value that satisfies it, and a narrower schema's renderer overrides
   it. VERIFIED (read).

2. **Inheritance is not used. The error pair is copied 279 times instead.**
   `:seon.error/base` declares no renderer
   (`resources/seon/schemas/seon.error.edn:290-296`). Every error schema
   repeats `:seon.render/ai seon.error/render-ai` instead. Probe: of 1,002
   shape rows, 344 carry `:seon.render/ai`, and **279 of those name
   `seon.error/render-ai`**. The next most common producer is
   `seon.error/index-refusal-prose` with 11. A canonical diagnostic built by
   `seon.error.refusal/diagnostic` matched 8 shapes, **`:seon.error/base`
   among them, and none declares a renderer**, so it falls to the floor
   printer (match cost 0.34 ms). Put the pair on `:seon.error/base` once, and
   by the specificity rule at `render.clj:334-380` that pair becomes every
   error's inherited default. That removes 279 copies, and each specific
   error can still override. VERIFIED (probe + read); the effect of the
   base declaration is UNVERIFIED by execution, because the freeze forbids
   the schema edit.

3. **Seon's "namespace override" belongs to the value's owner, not the
   viewer, and it is found by searching for a function whose contract fits.**
   The `:namespace` stage lists every public function in the value's owning
   namespace. It validates each function's input and output contract against
   the value and requires exactly one fit (`src/seon/render.clj:276-308`,
   `:519-543`). The walk sets that owner from refs, and when an entity refs
   two or more namespaces it sets no owner (`src/seon/render/walk.clj:599-613`).
   The walk docstring rules out the viewer entirely: it "never reads keyword
   text or the viewing agent's namespace" (`walk.clj:7-8`). So the owner's
   "other agents inherit unless they override" is half installed. The
   inherit half comes from the schema property. The override-by-viewer half
   does not exist, and the contract-fit search duplicates what the schema
   property already says. VERIFIED (read).

4. **`clojure.datafy/nav` is the right model for following links, and it
   costs nothing to adopt on the JVM side.** `nav [coll k v]` returns "(possibly
   transformed) v in the context of coll and k"
   (`reference-code/clojure/src/clj/clojure/datafy.clj:30-37`). Both
   protocols declare `:extend-via-metadata true`
   (`reference-code/clojure/src/clj/clojure/core/protocols.clj:181-202`).
   That means a render or read function can attach nav as metadata to a
   plain map: no type, no protocol extension, no stamp on stored data.
   Probe: an error map with a metadata nav resolved `:seon.error/member
   :seon.render/output` to its schema-registry row, 2 navs in 0.027 ms.
   next.jdbc and clojure.java.jdbc use the same pattern: a schema option
   names which columns are foreign keys, and nav fetches the related row
   (UNVERIFIED detail; summary at https://corfield.org/blog/2018/12/03/datafy-nav/).
   Seon's schemas already say which keys are refs and which are token values
   (`docs/seon/architecture/data-modeling-guide.md:80-100`). VERIFIED (read + probe).

5. **Agents cannot call nav today: the SCI world has no `clojure.datafy`.**
   Probe in SCI mode: `(resolve 'clojure.datafy/nav)`,
   `(resolve 'clojure.core.protocols/nav)` and `(find-ns 'clojure.datafy)` all
   returned false/nil. SCI ships no datafy namespace (grep of
   `reference-code/sci/src` finds none). Babashka adds one by copying the
   vars (`reference-code/babashka/src/babashka/impl/protocols.clj:38-47`,
   `babashka/main.clj:424`). No Seon SCI source mentions datafy (grep
   `src/seon/sci*`). Seon's own `datafy` use is limited to Flow graph
   introspection (`src/seon/flow.clj:267,416,1073,1171`,
   `src/seon/oversight.clj:106,145`). VERIFIED.
   *Side observation:* this trivial SCI evaluation reported
   `:seon.eval/duration-ms 833` and `:seon.eval/allocated-bytes 2576550088`
   (about 2.6 GB). Cause not diagnosed here; the ordinary-work-is-sub-second
   law flags it.

6. **Neither library datafies itself.** Datahike's `Entity`
   (`reference-code/datahike/src/datahike/impl/entity.cljc:53-150`)
   implements `ILookup` and `Associative` but not `Datafiable` or
   `Navigable`. Probe: `(identical? ent (datafy ent))` returned `true`.
   Malli has no datafy either (grep of `reference-code/malli/src` finds
   none). Nav over Seon data is therefore Seon's to write: one function over
   `seon.db` and the projection registry. VERIFIED.

7. **"Maybe just the description is enough?" Only 12% of keys have one.**
   Probe of the live projection registry: 3,373 qualified keys, **401 with
   `:description`**, 325 with `:seon.render/ai`. The eight core error keys
   (`:seon.error/at` … `:seon.error/fix`) have none. A link-follow that
   returns a key's `:description` would show nothing for most keys, so the
   nav target has to be the key's whole row: its form, owning namespace and
   producers or consumers by query. The description is optional text inside
   that row. VERIFIED (probe).

8. **Every viewer library converges on the same small rule.** It has three
   levels: a value-carried override, then a scoped table searched by
   predicate (first match or most specific), then a structural default.
   Clerk: `viewer-for` takes the viewer named on the value, otherwise the
   first `:pred` match; `get-viewers` resolves the value's own viewers, then
   the dynamic `*viewers*` binding, then the per-namespace `@!viewers`, then
   the defaults. Portal: the `:portal.viewer/default` metadata is moved to
   the front, then the list is filtered by `:predicate`. Reveal: a
   multimethod dispatching on `(or (::type (meta x)) (class x))`, so class
   and `derive` hierarchies give inheritance. Morse: the user picks a
   viewer, and nav moves forward and back. Seon's `:explicit-value`, `:schema`
   and `:floor` stages are that rule. Its `:explicit-request`, `:namespace`
   and attribute-scoped branches are extras. VERIFIED (source, §2.2).

9. **Links in values are already data, but nothing resolves them.**
   `seon.print/references` collects every symbol and identity lookup in a
   print node (`src/seon/print.cljc:831-866`). The walk matches symbol and
   string spellings of the same identity because "a function identity is
   stored as a string" (`src/seon/render/walk.clj:818-835`). That is a
   producer defect under the preserve-native-values law, not something the
   walk should work around. No `[[qualified/symbol]]` wikilink reader exists
   anywhere in `src/seon` (grep for `wikilink`, `cljdoc`, `"\[\["`: no hits).
   VERIFIED (read + grep).

10. **Presentation limits are already applied once and must stay that way.**
    `seon.print/fit` is "the AI context generation boundary's one elision"
    (`src/seon/print.cljc:1294-1300`). `seon.render.value/prepare` applies it
    only for `:seon.render/ai`, with a shared token-budget volatile across the
    tree walk (`src/seon/render/value.clj:585-632`, budget `:596-600`). A
    selected renderer shapes a reached map *before* the structural limits
    (`value.clj:449-461`). Clerk works the same way: one `:nextjournal/budget`
    (default 200) is decremented across the traversal
    (`clerk/viewer.cljc:1718-1724`), and each viewer declares a `:page-size`
    (`:916-933`). VERIFIED.

## 2. Evidence

### 2.1 clojure.datafy and nav

- `datafy` returns `p/datafy x`. When the result is new and supports
  metadata, it records the original under `:clojure.datafy/obj` and
  `:clojure.datafy/class` (`datafy.clj:15-28`). Throwable, IRef, Namespace
  and Class are extended (`:42-62`). `Throwable->map` is how an exception
  becomes data. VERIFIED.
- The default `nav` is identity: `(nav [_ _ x] x)` on Object
  (`core/protocols.clj:199-202`). VERIFIED.
- The model is to datafy, look up with ordinary Clojure functions, then nav
  to "the corresponding new thing" and repeat. REBL and Morse drill down by
  calling nav (UNVERIFIED wording, Corfield URL above). Morse's forward
  button "navs into the data selected" and back returns through the history;
  a `nav->` path counts as one step
  (https://github.com/nubank/morse/blob/main/docs/ui.adoc, lines 79-96).
  VERIFIED (fetched).
- **Fit for "error → function → schema key → description".** Each hop is
  `(nav coll k v)` where `k` is the key the agent followed:
  - `(nav error :seon.error/operation 'ns/f)` → the function row for `ns/f`
    (source, docstring, contract).
  - `(nav fn-row :seon.fn/contract c)` → the schema rows named inside `c`.
  - `(nav schema-row :seon.schema/key k)` → the key row with its
    `:description`, its producers and consumers.
  Each step is a pure function of one database value plus the key followed.
  That is the "derive from held immutable values" law. nav gives no loop,
  history or budget; the caller supplies those. That matches Seon's
  separation of the walk distance from the renderer (`walk.clj:13-21`).
  VERIFIED design fit (read). Probe shape in §2.4.

### 2.2 Viewer selection elsewhere: the rule each uses

| Tool | Pick rule | Narrow-scope override | Source |
|---|---|---|---|
| Clerk | `viewer-for`: the viewer named on the value, otherwise the first `:pred` match; no match throws | `get-viewers`: value's own `->viewers` → dynamic `*viewers*` → `@!viewers` for the scope (namespace name) → `:default`. `add-viewers!` resets the current namespace's list to `(add-viewers defaults new)`, which prepends by `:name` and replaces a same-named viewer | `clerk/viewer.cljc:520-526, 756-760, 1481-1497, 1935-1942` (commit `4c259994`), https://github.com/nextjournal/clerk/blob/main/src/nextjournal/clerk/viewer.cljc |
| Portal | Default viewer from metadata `:portal.viewer/default` (context props, value meta, or context), moved to the front, then filter by `:predicate`; a user choice per location wins | Value metadata; a per-location selection in state | `portal/ui/inspector.cljs:117-155`, https://github.com/djblue/portal/blob/master/src/portal/ui/inspector.cljs |
| Reveal | `defmulti stream-dispatch` on `(or (::type (meta x)) (class x))` | Metadata `::type`; multimethod hierarchy (`isa?`/`derive`) gives inheritance | `vlaaad/reveal/stream.clj:90-95, 628`, https://github.com/vlaaad/reveal/blob/master/src/vlaaad/reveal/stream.clj |
| Morse/REBL | Several applicable viewers; the user chooses; nav drives traversal | User choice only | ui.adoc above (registry internals UNVERIFIED) |

All VERIFIED from downloaded source except where noted. The shared shape
has three parts. **(a)** A value may name its own renderer. **(b)**
Otherwise, search a table visible at the narrowest scope that has one.
**(c)** Otherwise, a structural default. Clerk's scope is the *viewing*
notebook's namespace, not the value's owner. Clerk also requires names on
viewers so a narrower list can replace a same-named broader one
(`merge-viewers`, `viewer.cljc:749-754`).

### 2.3 Seon's installed render system

Line counts (VERIFIED, `wc -l`): `render.clj` 1,887; `render/web.clj` 3,795;
`render/transcript.clj` 2,379; `render/walk.clj` 975; `render/ns.clj` 836;
`render/value.clj` 715; `render/hiccup.clj` 525; `render/data.clj` 235;
`render/test.clj` 115; `render/block.clj` 96; `render/route.clj` 58;
`render/agent.clj` 24; `print.cljc` 1,367. **Total 13,007.** Schema
declarations: 350 `:seon.render/ai` and 345 `:seon.render/html` occurrences
across 70 of 210 schema files.

How one value's AI renderer is chosen today (all VERIFIED, read):

1. `:explicit-value`: the value itself carries `:seon.render/ai`
   (`render.clj:494-507`). This is Clerk's `->viewer`, Portal's metadata.
2. `:explicit-request`: the request carries it (`:509-517`).
3. `:namespace`: exactly one public function in `:seon.render/namespace`
   whose contract accepts the value and returns `:seon.render/ai` (or
   source) (`:276-308`, `:519-543`, `source-producer?` `:250-274`). The walk
   supplies the namespace (`walk.clj:764-774`). Bootstrap sets it for
   namespace values (`src/seon/bootstrap.clj:324`).
4. `:schema`: the most specific satisfied schema that declares the property
   (`:334-389`, `:545-565`). For an attribute-scoped request the stage
   reads only that attribute's own property (`:391-411`, `:445-451`).
5. `:floor`: `seon.render.value/render-ai`, or `render-default-ai-source`
   for source output (`:485-492`).

The selected symbol resolves to the live SCI Var and runs through
`seon.sci.kernel` (`render.clj:1-12`). Every value is selected by the same
rule, but nested maps are selected again only when the floor reaches them
with a SCI ctx (`value.clj:449-461` calling `render/project-node`,
`render.clj:1105-1219`). Clipping happens once in `print/fit` for AI; HTML
is never bounded (`print.cljc:1294-1300`, `transcript.clj:1685`).

### 2.4 REPL probes (JVM, `default`, read-only)

```clojure
;; P1 shape matches for a canonical diagnostic
(let [db (seon.db/db (seon.cluster.boot/connection "default"))
      p  (seon.db/carried-projection db)
      e  (seon.error.refusal/diagnostic (java.util.Date.) :seon.render/render
           'seon.render/ambiguity {...message fix member expected offending...})]
  (mapv (juxt :seon.schema/key :seon.render/ai) (seon.schema/matching-shapes-in p e)))
;; => 8 matches, all :seon.render/ai nil, incl. [:seon.error/base nil];
;;    0.34 ms; 1002 shape rows, 344 with :seon.render/ai,
;;    seon.error/render-ai named by 279 of them

;; P2 metadata nav (no protocol extension, no def)
(vary-meta e assoc `clojure.core.protocols/nav
           (fn [_ k v] (if (qualified-keyword? v) (describe v) (describe k))))
(clojure.datafy/nav e :seon.error/member :seon.render/output)
;; => {:schema/key :seon.render/output}   ; 2 navs 0.027 ms
(identical? ent (clojure.datafy/datafy (datahike.api/entity db [:seon.ns/name 'seon.render])))
;; => true (Entity is not Datafiable)

;; P3 descriptions in the live registry (properties via m/deref)
;; => 3373 qualified keys, 401 :description, 325 :seon.render/ai;
;;    :seon.error/{at,layer,operation,member,expected,offending,message,fix} -> nil

;; P4 SCI mode
[(some? (resolve 'clojure.datafy/nav)) (some? (resolve 'clojure.core.protocols/nav))
 (some? (find-ns 'clojure.datafy))]
;; => [false false false]   duration 833 ms, allocated 2,576,550,088 bytes
```

Failed probes are recorded too. `seon.schema/shape-projection` is private.
`matching-shapes` refuses without an operation projection, and
`seon.render/selection` refuses without `:seon.sci.eval/ctx`. Both are
declared refusals, not defects. The first attempt to read properties from
`m/schema k` returned nil for every key: `m/deref` is needed to see a named
schema's properties (`malli.core`). The owner of nav needs to know this.

## 3. Proposal: the smallest composition

**The rule (one sentence).** Take any value. If it carries
`:seon.render/ai` (or the output asked for), use that. Otherwise use the
producer declared by the **most specific schema it satisfies**. Otherwise
use the attribute-map floor. The declaring schema's owning namespace owns
its renderer; every other agent inherits it. A broader schema
(`:seon.error/base`, a future `:seon.fn/row`) gives the default for
everything that satisfies it. This is Clerk's first-match table with
Malli's validators as `:pred`, and specificity in place of list order.
Seon already runs it (`render.clj:334-389`).

**Links.** A qualified keyword or symbol inside a rendered value, or a
`[[qualified/symbol]]` inside a docstring or `:description`, is an observed
token, so it is a value, never a ref (`data-modeling-guide.md:80-86`).
Following one is `(clojure.datafy/nav coll k v)`. It is implemented once
and attached as metadata by the read and render entry points. It resolves:
- qualified keyword → that key's schema row (form, `:description`,
  declaring namespace, renderer, and producers/consumers by query);
- qualified symbol → the function row (docstring, contract, callers by
  query) or, when no row exists, the library var (`clojure.repl/source-fn`
  over `reference-code`, which primes the pump with idiomatic library code);
- lookup ref or identity map → `seon.db/pull` with the schema's selector.

The agent maps nav over a list like any other data, which answers the
owner's question about running a `map` over search results.

**Steps and cost** (estimated; the freeze stops implementation):

| Step | Change | Lines |
|---|---|---|
| S1 | Declare the AI/HTML pair once on `:seon.error/base`; delete 279 repeated properties (one scripted EDN sweep); keep the 10 distinct error producers as overrides | −279 EDN property copies, +1 |
| S2 | Delete the `:namespace` contract-fit stage and `source-producer?` (`render.clj:250-308, 519-543`) and the walk's owner derivation (`walk.clj:599-613`, `764-781` owner branch). The schema property already names the owning namespace's function | −110 to −140 src |
| S3 | Fold `:explicit-request` into explicit-value (the caller assocs the key on the unit) | −15 |
| S4 | `seon.render/nav`: one function of `[db projection coll k v]` for the three link kinds above; attach via `vary-meta` at `seon.db` read results and the floor's prepared projection only | +40 to +60 |
| S5 | Expose `clojure.datafy` (`datafy`, `nav`) and `clojure.core.protocols/nav` in the SCI context by `sci/copy-var`, as babashka does | +8 to +12 |
| S6 | `[[sym]]` token extraction from docstrings/descriptions into the same reference set as `print/references` (`print.cljc:831`) | +15 to +25 |

Net source: roughly **−80 to −40 lines**, plus the removal of 279
declaration copies. S1 and S2 are deletions that make the installed rule
visible. S4–S6 add under 100 lines together. The walk's `reference-keys`
symbol/string normalization (`walk.clj:818-835`) is deleted once function
identity is stored as a symbol at its producer. That needs its own fix at
the owner, outside this deliverable.

**What this does not replace, stated plainly.** Most of the 13k lines are
not selection. `web.clj` (3,795) and `transcript.clj` (2,379) are surface
assembly: pages, SSE, prompt units and the history of shown text. `print.cljc`
(1,367) is the REPL-faithful text and hiccup emitter. The rule and nav
shrink the selection and traversal seams (`render.clj` selection about
400 lines, `walk.clj` link-following). They do not by themselves take
13k to 2k. To shrink transcript and web, each surface's hand-built
sections have to be recast as schema-declared renderers over their
entities, audited one surface at a time. That is UNVERIFIED as a size
estimate and needs its own assignment.

**What must stay.** One AI/HTML pair per entity schema. The attribute-map
floor as the total default. `print/fit` as the only AI elision, with HTML
never clipped. The ambiguity error for equally specific distinct producers.
Selected-renderer failure staying local, with no fall-through
(`render.clj:5-8`). The walk's distance as an argument the renderer may
read. Nav does not replace the walk's pre-pulled prompt neighbourhood. A
prompt still needs context before the agent asks. The walk should compute
its hops with the same nav function, not a second link resolver.

## 4. Open questions for the owner

**Q1. Where does "override" live, owner or viewer?**
- (A, recommended) **Owner only.** The declaring schema's namespace owns
  the renderer. A namespace that wants a different view of foreign data
  declares a narrower schema of its own (for example, the foreign keys
  plus its own) with its own pair. Specificity then picks it wherever the
  value satisfies it. Nothing new is stored. Cost: S1+S2.
  Sacrifice: a viewer cannot restyle an identical shape without a
  distinguishing key.
- (B) **Viewer table, Clerk-style.** The viewing agent's namespace entity
  carries a map from schema key to producer symbol, consulted before the
  schema stage. About 30 lines plus a config attribute. Sacrifice: renders
  now depend on who is looking, and shown text needs the viewer in its
  cache key.
- (C) **Multimethod hierarchy, Reveal-style** (`derive` between schema keys).
  Sacrifice: one global JVM hierarchy conflicts with per-branch SCI
  programs, and it duplicates Malli's satisfaction test.

**Q2. What does following a qualified-keyword link return?**
- (A, recommended) **The key's row**: form, `:description` when present,
  declaring namespace, renderer, and producer/consumer queries, rendered by
  its own pair. Works for the 88% of keys without a description.
- (B) Only `:description`. The smallest option, but it shows nothing for
  most keys, including every core error key (P3).
- (C) (A) plus a sweep to require `:description` on every key.
  3,373 keys; a documentation assignment, not a mechanism.

**Q3. Where is nav attached?**
- (A, recommended) **Metadata on read results and prepared projections**
  (`extend-via-metadata`, `core/protocols.clj:182,194`): no types, nothing
  stored, and it vanishes when the value is serialized, which is correct.
- (B) `extend-protocol Navigable` on `IPersistentMap` globally. This
  changes nav for every map in the JVM, including library data.
- (C) A Seon-only `follow` function without the protocol. Simple, but Morse
  and Portal could no longer browse the same data.

**Q4. Reference-code vars as nav targets.**
- (A, recommended) Resolve by `clojure.repl/source-fn` and `meta` against
  the classpath (`reference-code` is vendored source). No rows needed.
- (B) Index reference-code into program rows so it renders through the same
  pairs. More uniform, but adds a large read-only population and an
  indexing cost that has to be measured first.
