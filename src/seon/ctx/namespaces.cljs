(ns seon.ctx.namespaces
  "The `:namespaces` context section — THE BODY of the prompt
   (context-v4 §2.3): CURATED, not render-everything (curated-namespaces
   2026-06-21). The old render dumped EVERY seon.* framework ns as a
   compact `(ns …)` + `register!` + elided-defn surface — 70+ tags of
   mostly-noise the agent never calls. That render is GONE. Now:

     - FULL source (whole file, NO clipping) for the few nses an agent
       actually USES or OWNS:
         (a) every `my.*` ns           — the human's/agent's own code;
         (b) every THIRD-PARTY ns      — non-seon, non-my (the
             `SEON_EXTRA_SRC` `acme` business logic the agent needs whole);
         (c) the agent's CURRENT ns    — its complete working code;
         (d) a small curated whitelist of `seon.*` framework tools
             ([[seon.ctx/exemplar-nses]] — `:seon.agent.todo`).
       The FULL-source decision is one shared rule:
       [[seon.ctx/full-source-ns?]] already covers (a) + (d); (b) is the
       not-`seon.` structural fall-through; (c) is the current-ns check.
     - NAME-MANIFEST for every OTHER `seon.*` framework ns: one block
       listing just the names + a pointer to fetch any one's source on
       demand. No bodies, no `register!` dump.

   Symbol-wired into the composer layout (`seon.ctx/core-default-ctx`) as
   `'seon.ctx.namespaces/namespaces-section`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`.

   The section NEVER re-reads files at render time (code-as-data): the
   boot indexer (`seon.client/ns-row`) is the ONE file-reader, and it
   stores the REAL full file text for exactly the nses rendered full
   (the same [[seon.ctx/full-source-ns?]] rule + the extra-src roots),
   leaving the framework bulk a `(ns x)` stub — which this section never
   renders as a body, only as a NAME in the manifest. So the full rows
   here are always real file source, never a reconstructed stub."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]))

(defn- third-party-ns?
  "A render-time structural rule: an included ns that is NEITHER `seon.*`
   framework NOR `my.*` is THIRD-PARTY business logic (the `acme`
   `SEON_EXTRA_SRC` code) — rendered FULL, no clipping. `my.*` is full via
   [[seon.ctx/full-source-ns?]] already; this catches the remaining
   non-seon roots. String/keyword tolerant."
  [ns-name]
  (let [s (if (keyword? ns-name) (name ns-name) (str ns-name))]
    (not (str/starts-with? s "seon."))))

(defn- render-full?
  "True when ns `nm` (a keyword) renders its FULL source in the body:
     (a) it is the agent's CURRENT ns (`cur-ns`); OR
     (b) [[seon.ctx/full-source-ns?]] — every `my.*` ns + the curated
         seon.* whitelist ([[seon.ctx/exemplar-nses]]); OR
     (c) [[third-party-ns?]] — a non-seon, non-my root (the `acme`
         business logic).
   Everything else is a `seon.*` framework ns → NAME-MANIFEST only."
  [nm cur-ns]
  (boolean
    (or (= nm cur-ns)
        (ctx/full-source-ns? nm)
        (third-party-ns? nm))))

(defn- manifest-block
  "The ONE name-only block for the `seon.*` framework bulk: a `;;` comment
   listing every non-rendered framework ns NAME, with a clear pointer to
   query any one's source on demand. Returns nil when `names` is empty
   (nothing to manifest → no block)."
  [names]
  (when (seq names)
    (str ";; other seon framework namespaces (not shown full — query a fn's\n"
         ";; source by name when you need it, e.g.:\n"
         ";;   (seon.db/query '[:find ?sym ?src :where\n"
         ";;                    [?n :seon.ns/name :seon.warn]\n"
         ";;                    [?f :seon.fn/ns ?n]\n"
         ";;                    [?f :seon.fn/sym ?sym]\n"
         ";;                    [?f :seon.fn/source ?src]])\n"
         ";; (swap :seon.fn/ns·sym·source for :seon.schema/ or :seon.test/\n"
         ";;  to read that ns's schemas or tests the same way; or call\n"
         ";;  (seon.ctx/render-namespace {:seon.ns/name :the.ns}) for a\n"
         ";;  whole-ns view):\n"
         ";; "
         (str/join ", " (map name names)))))

(def ^:private namespaces-header
  (str ";; Real loaded code. The few namespaces you USE or OWN are shown in\n"
       ";; FULL (your my.* code, third-party business code, your current\n"
       ";; namespace, and a curated seon.* tool set); the rest of the seon\n"
       ";; framework is named in a manifest at the end — query any of those\n"
       ";; by name on demand. Full namespaces are ordered by RECENCY:\n"
       ";; most-recently-modified LAST."))

(defn namespaces-section
  "CURATED `<namespace>` body (curated-namespaces 2026-06-21). One
   `<namespace name=\"…\">` tag per FULL-rendered ns ([[render-full?]]:
   every `my.*` ns, every THIRD-PARTY `acme` ns, the agent's CURRENT ns,
   and the curated [[seon.ctx/exemplar-nses]] seon.* whitelist), each
   carrying its REAL FULL FILE SOURCE — NO clipping. Every OTHER `seon.*`
   framework ns ([[seon.ctx/included-ns?]] minus the full set) collapses
   into ONE name-only [[manifest-block]] at the end, with a clear
   query-for-source pointer.

   The full tags are ordered by RECENCY (tx of the `:seon.ns/name` datom —
   bumped by the tee's nested upsert on every define), name as the
   tie-break, so the stable core forms a stable cache prefix and the
   churning ns sits nearest the tail. The manifest is name-sorted and
   sits LAST (it changes only when the framework roster changes).

   `*.internal` and `*-test` nses are excluded outright
   ([[seon.ctx/included-ns?]]). A full-source ns whose stored source is
   blank renders nothing (omitted); the boot indexer guarantees real text
   for every full row, so this is only the empty-store edge. NEVER a
   render-time file read — the boot indexer is the one reader."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] id :seon.agent/id}]
  (let [;; The agent's current ns (latest successful eval's ns) → rendered
        ;; FULL even if it is a framework ns. nil id (inspector path) →
        ;; nil → no ns is forced current.
        cur-ns (when id
                 (try (ctx/current-ns {:seon.agent/id id :seon.db/db db})
                      (catch :default _ nil)))
        ;; Sources joined SEPARATELY from the name rows (requiring
        ;; :seon.ns/source in the join silently drops sourceless rows; a
        ;; plain :where on a registered-but-uninstalled attr returns empty,
        ;; never throws). Looked up in code below.
        sources (into {}
                      (db/query
                        {:seon.db/db db
                         :seon.db/query
                         '[:find ?nm ?src
                           :where
                           [?n :seon.ns/name ?nm]
                           [?n :seon.ns/source ?src]]}))
        ;; EVERY included ns row, recency-ordered, partitioned into the
        ;; FULL set (rendered as tags) and the framework bulk (manifest).
        rows   (->> (db/query
                      {:seon.db/db db
                       :seon.db/query
                       '[:find ?nm ?tx
                         :where
                         [?n :seon.ns/name ?nm ?tx]]})
                    (filter (fn [[nm _]] (ctx/included-ns? nm)))
                    (sort-by (fn [[nm tx]] [tx (name nm)])))
        {full-rows true manifest-rows false}
        (group-by (fn [[nm _tx]] (render-full? nm cur-ns)) rows)
        ;; FULL tags — real file source, trimmed, NO clipping. A blank
        ;; source (empty-store edge) yields no tag.
        tags   (keep
                 (fn [[nm _tx]]
                   (let [src (str/trim (str (get sources nm)))]
                     (when-not (str/blank? src)
                       (str "<namespace name=\"" (name nm) "\">\n"
                            src
                            "\n</namespace>"))))
                 full-rows)
        ;; The framework bulk → ONE name-only manifest block, name-sorted.
        manifest (manifest-block
                   (sort (map (comp name first) manifest-rows)))
        blocks (cond-> (vec tags)
                 manifest (conj manifest))]
    (if (seq blocks)
      (str namespaces-header "\n\n" (str/join "\n\n" blocks))
      "")))
