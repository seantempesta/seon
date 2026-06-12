(ns seon.agent.findings
  "Stored findings as CONTEXT — the derived salience rung (`:findings`,
   substrate-default-ctx priority 48): the CONTENT of every user-domain
   row in the shared store, rendered into the prompt so agents read
   stored knowledge instead of answering from priors or re-deriving it
   from the repo. `seon.db/store-inventory` teaches what EXISTS (attr
   names + counts); this section shows what it SAYS.

   STRUCTURAL, never a name-list (uniformity rule — one mechanism, no
   `:my.kb` special case): a kind renders here iff
     (a) it is USER-DOMAIN — its attr namespace is NOT in the
         substrate-kind set, derived per render from tx provenance
         (the namespaces whose `:seon.schema/key` rows were asserted
         under a `:substrate-seed` boot-index tx — the SAME rule
         `seon.db/store-inventory` orders by, so an agent-minted kind
         qualifies whatever its keyword spelling), and
     (b) it has READABLE CONTENT — at least one live string value
         under one of its own attrs (pure-numeric tally kinds carry
         nothing to read; querying them is taught elsewhere).

   Reactive-context principle: a pure function of the render's db
   value. Rows appear the render after they are transacted and vanish
   the render after they are retracted — nothing stored, nothing to
   acknowledge. No `:seon.agent/id` filter, deliberately: every agent
   sees every agent's stored findings (cross-agent accumulation is the
   point).

   Substrate-authored context renders IN FULL; a pathological kind is
   LOUDLY truncated (never a quiet clip — the observed failure mode is
   an agent summarizing invented content from a silent clip) with the
   exact query that reads the rest."
  (:require
    [cljs.pprint :as pprint]
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

(def kind-render-cap
  "Per-kind rendered-chars BACKSTOP, not a working limit — findings
   are expected to fit whole far below it (same stance as
   `seon.ctx/substrate-eval-render-cap`). Over it, the kind's tail is
   replaced by a loud marker carrying the query that reads the
   complete rows."
  20000)

(defn- substrate-kinds
  "Attr namespaces (keywords) whose `:seon.schema/key` rows were
   asserted under a `:substrate-seed` boot-index tx. The same
   provenance derivation `seon.db/store-inventory` uses for its
   user-domain-first ordering (kept textually in sync; smell on
   record: `seon.db` should expose the flag once, per-row)."
  [db]
  (into #{}
        (keep (fn [[k]] (some-> (namespace k) keyword)))
        (db/query {:seon.db/db db
                   :seon.db/query '[:find ?k
                                    :where
                                    [?tx :seon.db/origin :substrate-seed]
                                    [?s :seon.schema/key ?k ?tx]]})))

(defn- kind-rows
  "Every row carrying at least one of `attr-ks`, pulled `[*]`, ordered
   by `:db/id` ascending (insertion order — deterministic for a given
   db value)."
  [db attr-ks]
  (->> (db/query {:seon.db/db db
                  :seon.db/args [attr-ks]
                  :seon.db/query '[:find [?e ...]
                                   :in $ [?a ...]
                                   :where [?e ?a _]]})
       sort
       (mapv #(db/pull db '[*] %))))

(defn- string-content?
  "True when some row of `rows` carries a live string value under an
   attr of `kind`'s own namespace (cardinality-many values count
   element-wise)."
  [kind rows]
  (let [knm (name kind)]
    (boolean
      (some (fn [row]
              (some (fn [[a v]]
                      (and (keyword? a)
                           (= knm (namespace a))
                           (or (string? v)
                               (and (sequential? v) (some string? v)))))
                    row))
            rows))))

;; The renderable-kind entry shape — shared by [[findings-block]] (the
;; agent's `:findings` context rung) and the inspector's findings pane
;; (`seon.web.inspector`), so the dashboard and the prompt read ONE
;; derivation (shared-shape rule — no twin query). `:seon.db/kind` /
;; `:seon.db/attrs` are `store-inventory`'s own registered shapes,
;; referenced, not re-inlined.
(schema/register! ::rows [:vector :map])
(schema/register! ::kind-entry
  [:tuple :seon.db/kind :seon.db/attrs ::rows])

(defn user-domain-kinds
  "The renderable kinds of db value `db` — `[[kind attrs rows] …]`,
   kind-name order (deterministic). Selection rules (a) + (b) from the
   ns docstring. PUBLIC: the inspector findings pane derives its
   per-kind summary from this same fn (one truth for 'what has this
   cluster learned' — prompt and dashboard can never disagree)."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val]]
                  [:vector ::kind-entry]]}
  [db]
  (let [sub (substrate-kinds db)]
    (->> (db/store-inventory {:seon.db/db db})
         (remove #(contains? sub (:seon.db/kind %)))
         (keep (fn [{:seon.db/keys [kind attrs]}]
                 (let [rows (kind-rows db (vec (keys attrs)))]
                   (when (string-content? kind rows)
                     [kind attrs rows]))))
         (sort-by (comp str first))
         vec)))

(defn- render-row
  "One pulled row as a pretty-printed map, keys sorted (byte-stable
   output for a given db value)."
  [row]
  (str/trimr (with-out-str (pprint/pprint (into (sorted-map) row)))))

(defn- read-query-hint
  "The copy-paste query reading a kind's complete rows, anchored on
   its most-populated attr (covers the most rows when rows are
   sparse)."
  [attrs]
  (str "(seon.db/query '[:find (pull ?e [*]) :where [?e "
       (key (apply max-key val attrs)) " _]])"))

(defn- cap-kind
  "[[kind-render-cap]] over one kind's rendered rows — pass-through
   below it, LOUD truncation marker + the read-back query over it."
  [body hint]
  (let [n (count body)]
    (if (<= n kind-render-cap)
      body
      (str (subs body 0 kind-render-cap)
           "\n;; ⚠ TRUNCATED at " kind-render-cap " of " n
           " chars — the DISPLAY is clipped, the stored rows are"
           " COMPLETE. Read the rest yourself:\n"
           ";;   " hint))))

(defn findings-block
  "The `<findings>` context block for db value `db`: every user-domain
   kind's rows IN FULL, with their provenance attrs, deterministic
   order (kinds by name, rows by `:db/id`). \"\" when the store holds
   no user-domain content — the section vanishes (derived, nothing
   stored, nothing to acknowledge)."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val]] :string]}
  [db]
  (let [kinds (user-domain-kinds db)]
    (if (empty? kinds)
      ""
      (str "<findings>\n"
           ";; STORED KNOWLEDGE — every user-domain row in the shared store,\n"
           ";; rendered IN FULL as-of this render (other agents' writes\n"
           ";; included; retracted rows vanish). CONSULT BEFORE RESEARCHING:\n"
           ";; when a row below already answers the question, cite it — and\n"
           ";; its provenance — instead of re-deriving it from the repo.\n"
           (str/join "\n"
             (map (fn [[kind attrs rows]]
                    (let [hint (read-query-hint attrs)]
                      (str "\n;; " kind " — " (count rows)
                           (if (= 1 (count rows)) " row" " rows")
                           "; re-read: " hint "\n"
                           (cap-kind (str/join "\n" (map render-row rows))
                                     hint))))
                  kinds))
           "\n</findings>"))))

(defn findings-section
  "Context-section fn (`:findings`, substrate-default-ctx priority 48):
   [[findings-block]] over the render's db snapshot — absent
   `:seon.db/db` defaults to the current conn, the same convention as
   every sibling section fn."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (findings-block (or db @db/*conn*)))
