(ns seon.agent.ctx.findings
  "The stored-findings context section — the CONTENT counterpart to
   `seon.agent.ctx.inventory` (which renders COUNTS for discoverability).
   A fresh agent must SEE the knowledge already in the store, not just be
   told how many rows exist — otherwise it under-stores and re-researches
   instead of consulting (the DB-memory regression this restores). So this
   renders the TOP-N most-recent user-domain `my.*`/consumer rows' actual
   claim/answer TEXT plus their shared `:my.kb/source-*` PROVENANCE, one
   compact `;`-comment line per row.

   BOUNDED, not the old unbounded `pull[*]` dump: capped row count, capped
   per-row content, a loud-truncation footer carrying the read-back query
   when clipped. No relevance/lexical-overlap pointer (that's deferred to
   embeddings/Proximum — see `seon.agent.ctx.relevant`).

   Symbol-wired into the composer layout
   (`seon.config/default-ctx-blocks`) as
   `'seon.agent.ctx.findings/findings-block`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`. Rides the VOLATILE band
   (priority > cache-breakpoint) so a newly-stored finding never busts
   the cached stable prefix.

   REACTIVE: returns \"\" when the store holds no user-domain rows → the
   composer drops the section (no empty shell)."
  (:require
    [clojure.string :as str]
    [seon.db :as db]))

(def ^:private max-rows
  "How many most-recent findings to render inline. Bounded so the section
   never grows unbounded as the store accumulates — older rows live behind
   the read-back query in the truncation footer."
  10)

(def ^:private content-char-cap
  "Per-row cap on the rendered claim/answer text. A longer claim is clipped
   with an inline `…`; the agent pulls the full row by eid. Keeps the worst
   case (max-rows × this) a few hundred tokens — a volatile-tail section,
   not a budget-blower."
  220)

(def ^:private provenance-attrs
  "The shared `:my.kb/*` provenance attrs every domain references (NOT a
   per-domain fork). These render as the row's source citation, not as its
   content."
  #{:my.kb/source-path :my.kb/source-line :my.kb/source-line-end
    :my.kb/verified-at :my.kb/confidence})

(def ^:private findings-header
  (str "; stored findings — your accumulated knowledge, most-recent first.\n"
       "; Each line: the claim + where it came from. CONSULT these BEFORE\n"
       "; re-researching; pull a full row with (seon.db/pull '[*] <eid>).\n"
       "; Store new findings as my.<domain> rows with :my.kb/source-* provenance."))

(def ^:private read-back-query
  "The read-back the truncation footer hands the agent — lists every stored
   finding by its shared provenance anchor (the same attrs s32's consult
   query uses), so the agent can pull the rows beyond the rendered cap."
  (str "(seon.db/query '[:find ?e ?path ?line :where "
       "[?e :my.kb/source-path ?path] [?e :my.kb/source-line ?line]])"))

(defn- user-attr?
  "True when `a` is a user/consumer-domain attribute — namespaced, and NOT
   the framework's own infra (`seon.*`, `:db/*`/`datahike.*` system attrs).
   Mirrors the inventory's `seon.`-prefix exclusion so consumer namespaces
   (acme, …) count as user-domain too, not just `my.*`."
  [a]
  (let [ns (namespace a)]
    (boolean
      (and ns
           (not (str/starts-with? ns "seon"))
           (not (str/starts-with? ns "db"))
           (not (str/starts-with? ns "datahike"))))))

(defn- finding-eids
  "The most-recent user-domain entity ids in `db`, newest first (sort by
   `:db/id` desc — the same monotonic boot-scope exclusion `inventory`
   uses). An entity is user-domain when it carries any [[user-attr?]]
   attribute and is NOT a boot-scope row (`boot-ids`,
   [[seon.db/bootstrap-row-ids]]). Returns `[capped-eids total-count]` so
   the caller knows whether to render the truncation footer."
  [db boot-ids]
  (let [pairs (db/query {:seon.db/db db
                         :seon.db/query '[:find ?e ?a :where [?e ?a ?v]]})
        eids  (into #{}
                    (keep (fn [[e a]]
                            (when (and (not (contains? boot-ids e))
                                       (user-attr? a))
                              e)))
                    pairs)
        sorted (sort > eids)]
    [(take max-rows sorted) (count eids)]))

(defn- clip
  "Collapse whitespace and cap a claim string to [[content-char-cap]] with
   an inline `…` so a long claim never blows the line budget (the agent
   pulls the full row)."
  [s]
  (let [s (-> s str/trim (str/replace #"\s+" " "))]
    (if (> (count s) content-char-cap)
      (str (subs s 0 content-char-cap) "…")
      s)))

(defn- provenance-str
  "The source citation `[path:line(-end) :confidence]` for a pulled row, or
   \"\" when it carries no `:my.kb/source-*` provenance."
  [m]
  (let [path (:my.kb/source-path m)
        line (:my.kb/source-line m)
        end  (:my.kb/source-line-end m)
        conf (:my.kb/confidence m)
        loc  (when path
               (str path (when line (str ":" line (when end (str "-" end))))))
        bits (remove nil? [loc (when conf (str conf))])]
    (if (seq bits)
      (str "  [" (str/join " " bits) "]")
      "")))

(defn- row-line
  "Render ONE finding as a single `;`-comment line: its domain namespace,
   eid (for pull), claim/answer TEXT, and `:my.kb/source-*` provenance.
   The content is the longest user-domain (non-provenance) string value on
   the row — the claim/answer — clipped to [[content-char-cap]]. Falls back
   to the row's user attrs printed compactly when it carries no string
   content. The whole line is a `;` comment so the context stays eval'able
   Clojure; the agent reads it, never evaluates it."
  [db eid]
  (let [m         (db/pull {:seon.db/db db :seon.db/pull-pattern '[*]
                            :seon.db/ref eid})
        user-kvs  (->> m
                       (filter (fn [[a _]] (user-attr? a)))
                       (remove (fn [[a _]] (contains? provenance-attrs a))))
        content-e (->> user-kvs
                       (filter (fn [[_ v]] (string? v)))
                       (sort-by (fn [[_ v]] (count v)))
                       last)
        dom-ns    (some-> (or (first content-e) (ffirst user-kvs))
                          namespace)
        content   (if content-e
                    (clip (val content-e))
                    (clip (pr-str (into {} user-kvs))))]
    (str "; " (when dom-ns (str dom-ns " ")) "#" eid ": "
         content (provenance-str m))))

(defn findings-block
  "The stored-findings content surface: recent claims + `:my.kb` provenance.

   Volatile tail — the TOP-N most-recent user-domain rows' actual
   claim/answer TEXT + their `:my.kb/source-*` provenance, one
   `;`-comment line each. The CONTENT
   sibling of [[seon.agent.ctx.inventory/inventory-block]] (counts for
   discoverability) — restores the salience render a fresh agent needs to
   consult-before-researching. Pure fn of the db; stores nothing; recomputed
   each render (a newly-stored finding appears next turn, a retracted one
   vanishes — see docs/seon/concepts/reactive-context). Boot-scope rows are
   excluded ([[seon.db/bootstrap-row-ids]]).

   BOUNDED: at most [[max-rows]] rows, each clipped to [[content-char-cap]];
   when more findings exist than are shown, a loud-truncation footer carries
   the read-back query so the agent can pull the rest. REACTIVE: returns
   \"\" when the store holds no user-domain rows → the composer drops the
   section."
  {:malli/schema [:=> [:cat :seon.render/section-request] :string]}
  [{:seon.db/keys [db]}]
  (let [boot-ids       (db/bootstrap-row-ids db)
        [eids total]   (finding-eids db boot-ids)]
    (if (seq eids)
      (let [lines    (map #(row-line db %) eids)
            hidden   (- total (count eids))
            footer   (when (pos? hidden)
                       (str "\n; … +" hidden " older finding"
                            (when (> hidden 1) "s")
                            " not shown — list every stored finding:\n;   "
                            read-back-query))]
        (str findings-header "\n\n"
             (str/join "\n" lines)
             footer))
      "")))
