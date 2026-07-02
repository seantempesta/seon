(ns seon.agent.ctx.inventory
  "The stored-data inventory context section — a cheap, reactive map of
   what the shared store holds RIGHT NOW (one line per attribute
   NAMESPACE with each attr's live row count), rendered as a single-`;`
   comment-block. Entities have no kind: this groups the live attributes
   by their namespace so the agent knows which attr's index to scan
   (attribute-presence) to FIND data. Symbol-wired into the composer
   layout (`seon.config/default-ctx-blocks`) as
   `'seon.agent.ctx.inventory/inventory-block`; loaded at boot so the
   symbol resolves for `seon.eval/lookup-value`."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.schema :as schema]))

(def ^:private value-cardinality-threshold
  "Skip the distinct-value query for an identity attr whose live row
   count exceeds this — a long ident list is noise, and a model never
   wants 500 keys inline. Enum attrs ignore this (their members come
   from the schema form, always low-card)."
  12)

(def ^:private value-sample-cap
  "Max distinct values rendered inline per attr (a `...` token is appended
   inside the brackets when the set was larger)."
  8)

(def ^:private value-token-char-cap
  "Per-VALUE length guard. A distinct value whose printed form is longer
   than this is a BLOB (source code, a token-usage JSON, a stack trace) —
   useless as a filter key and budget-blowing. If ANY shown value exceeds
   this the attr falls back to count-only: the inventory's job is to point
   at queryable KEYS, not to dump payloads."
  48)

(def ^:private lookup-value-base-types
  "Malli base types whose values are useful LOOKUP/FILTER keys to show
   inline (the categorical/identity surface an agent puts in a :where
   clause). Deliberately EXCLUDES `:seon.db/ref` (opaque entity ids),
   `:inst` (timestamps — not filter keys), and numeric types (rarely
   categorical). Containers of these (`[:vector :keyword]`,
   `[:set :string]`, …) qualify by their element type."
  #{:string :keyword :boolean :symbol})

(def ^:private inventory-header
  (str "; stored data — what this cluster holds NOW (post-bootstrap rows).\n"
       "; One line per attribute NAMESPACE: each attr NAME, its live count,\n"
       "; and «sample values» for low-card lookup columns. To read a row,\n"
       "; scan that attr's index — [?e :the/attr ?v]. The «..» are\n"
       "; ILLUSTRATIVE keys to query by — never quote one as the answer;\n"
       "; query for the real value. Consult BEFORE researching or registering."))

(defn- lookup-attr?
  "True when `attr`'s registered schema is a LOOKUP/FILTER type whose
   distinct values are worth showing inline — a string/keyword/boolean/
   symbol scalar (incl. an identity attr, which is such a scalar carrying
   `{:seon.db/identity true}`), or a vector/set of one. EXCLUDES refs
   (`:seon.db/ref` → opaque eids), timestamps (`:inst`), and numerics.
   Reads the schema form's base type ([[lookup-value-base-types]]); a
   1-level container qualifies by its element type."
  [attr]
  (let [form (schema/schema-definition attr)
        base (cond
               (keyword? form) form
               ;; scalar with props: [:string {:seon.db/identity true}],
               ;; [:keyword {…}] — head is the base type.
               (and (vector? form)
                    (contains? lookup-value-base-types (first form)))
               (first form)
               ;; container: [:vector :keyword] / [:set [:enum …]] /
               ;; [:vector {props} :string] — element is the LAST base kw.
               (and (vector? form)
                    (contains? #{:vector :set :sequential} (first form)))
               (last (filter keyword? (rest form)))
               :else nil)]
    ;; an identity attr is always a lookup key even if its base type form
    ;; uses an indirection (e.g. [:and {identity} :seon.db/id]).
    (or (contains? lookup-value-base-types base)
        (schema/identity-attr? attr))))

(defn- distinct-values
  "The POST-BOOTSTRAP distinct values of `attr` against `db`, sorted by
   printed form and capped at [[value-sample-cap]] (a trailing `...` marks
   truncation). Scopes OUT boot-index rows (`boot-ids`,
   [[seon.db/bootstrap-row-ids]]) so the value set matches
   [[seon.db/store-inventory]]'s post-bootstrap count — a global query
   would leak the thousands of boot-index idents the inventory hides.
   Container values (a `[:vector :keyword]` etc.) are flattened so the
   MEMBERS show, not the collection wrappers. Returns nil when ANY shown
   value is a BLOB (> [[value-token-char-cap]] chars) — the attr is a
   payload column, not a filter key, and falls back to count-only."
  [db boot-ids attr]
  (let [pairs  (db/query {:seon.db/db db
                          :seon.db/query [:find '?e '?v
                                          :where ['?e attr '?v]]})
        raw    (into #{} (keep (fn [[e v]]
                                 (when-not (contains? boot-ids e) v)))
                     pairs)
        ;; flatten container values to their members (a [:vector :keyword]
        ;; attr stores vectors; the agent filters on the MEMBER keys).
        vs     (into #{} (mapcat (fn [v] (if (coll? v) v [v]))) raw)
        sorted (sort-by pr-str vs)
        shown  (take value-sample-cap sorted)
        toks   (mapv pr-str shown)]
    (when (every? #(<= (count %) value-token-char-cap) toks)
      (cond-> toks
        (> (count sorted) value-sample-cap) (conj "...")))))

(defn- value-tokens
  "The distinct VALUES to render after an attr's count, or nil when the
   attr is a payload column (blob/ref/timestamp/numeric) or
   high-cardinality. ENUM attrs → members from the registered schema (NO
   db query, always shown). LOOKUP attrs ([[lookup-attr?]] — identity or
   any string/keyword/boolean/symbol scalar or container of one) → a
   post-bootstrap distinct query, but ONLY when the live count `c` is ≤
   [[value-cardinality-threshold]] and no value is a blob. Values print
   pr-str-style so `:kw` vs \"str\" reads unambiguously into a :where
   clause."
  [db boot-ids attr c]
  (let [members (schema/enum-members attr)]
    (cond
      (seq members) (mapv pr-str members)
      (and (lookup-attr? attr) (<= c value-cardinality-threshold))
      (distinct-values db boot-ids attr)
      :else nil)))

(defn- attr-token
  "Render ONE `attr count «v v ...»` token for the inventory line — the
   `«..»` value list is appended only when [[value-tokens]] returns some.
   The GUILLEMETS (not `[..]`) mark the values as an ILLUSTRATIVE SAMPLE,
   not an authoritative readout — the same de-literaled convention the rest
   of the context uses — so an agent treats them as example filter keys to
   query, never as the stored answer to quote. The whole line is a `;`
   comment, so the glyphs never break the reader."
  [db boot-ids a c]
  (let [vs (value-tokens db boot-ids a c)]
    (if (seq vs)
      (str (name a) " " c " «" (str/join " " vs) "»")
      (str (name a) " " c))))

(defn inventory-block
  "The stored-data discovery surface: what the shared store holds right now.

   An always-changing volatile tail — a CHEAP map of the store's current
   contents, derived from [[seon.db/store-inventory]] (user-domain
   namespaces first). ONE
   line per attribute NAMESPACE — the namespace is the line label, then
   space-separated `attr-name count` pairs with the namespace stripped
   off each attr name (the line label already carries it). Entities have
   no kind; the namespace is just a grouping — to read a row, scan that
   attr's index (`[?e :the/attr ?v]`). LOW-CARDINALITY identity/enum
   attrs also get an ILLUSTRATIVE SAMPLE of their DISTINCT values inline
   as `attr count «v v ...»` ([[value-tokens]]) so an honest query lands
   on real keys instead of a guessed-then-empty ident; the guillemets
   mark the sample as non-authoritative (query for the actual value,
   never quote the sample) — enum members come from the schema (no
   query), identity values from ONE capped distinct query when the count
   is ≤ [[value-cardinality-threshold]]. Pure fn of the db; stores
   nothing; recomputed each render so a newly-stored namespace appears
   next turn and a fully-retracted one vanishes (see
   docs/seon/concepts/reactive-context).

   REACTIVE: returns \"\" (composer drops the section) when the store
   holds no post-bootstrap data — no empty shell. The whole section for
   a typical store is only a few hundred chars (~300 tokens), so it
   stays out of the cacheable prefix and rides near the prompt tail."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db]}]
  (let [;; Show DOMAIN KNOWLEDGE only — `my.*` and any third-party (acme)
        ;; namespaces the human/agents stored. The framework's own runtime
        ;; exhaust (`seon.agent`, `seon.eval`, `seon.ns`, `seon.agent.turn`,
        ;; …) is post-bootstrap too, but it is machinery, not knowledge to
        ;; consult before researching — the transcript already shows the
        ;; agent's evals, the roster shows agents. Filtering it out is what
        ;; makes this surface answer "what KNOWLEDGE exists?" instead of
        ;; drowning the one `my.kb` line under bookkeeping. Empty after the
        ;; filter → "" → the section reactively vanishes (a fresh cluster
        ;; with no stored knowledge shows no inventory).
        rows (->> (:seon.db/attr-groups (db/store-inventory {:seon.db/db db}))
                  (remove (fn [{ns-kw :seon.db/attr-ns}]
                            (str/starts-with? (name ns-kw) "seon."))))]
    (if (seq rows)
      (let [;; ONE shared bootstrap-scope scan per render — values must be
            ;; post-bootstrap (matching store-inventory's counts), so a
            ;; low-card identity query excludes boot-index entities.
            boot-ids (db/bootstrap-row-ids db)
            ;; Each namespace row rides as a `;` comment so the whole section
            ;; reads as eval'able Clojure (the context IS one live REPL) —
            ;; the agent reads the row, queries the named attrs, never
            ;; evaluates this line.
            lines (map (fn [{ns-kw :seon.db/attr-ns attrs :seon.db/attrs}]
                         (str "; " (name ns-kw) ": "
                              (str/join " "
                                (map (fn [[a c]] (attr-token db boot-ids a c))
                                     attrs))))
                       rows)]
        (str inventory-header "\n\n"
             (str/join "\n" lines)))
      "")))
