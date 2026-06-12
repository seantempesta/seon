(ns seon.agent.findings
  "Stored findings as CONTEXT — the derived salience rung (`:findings`,
   substrate-default-ctx priority 48): the CONTENT of every user-domain
   row in the shared store, rendered into the prompt so agents read
   stored knowledge instead of answering from priors or re-deriving it
   from the repo. `seon.db/store-inventory` teaches what EXISTS (attr
   names + counts); this section shows what it SAYS.

   STRUCTURAL, never a name-list (uniformity rule — one mechanism, no
   `:my.kb` special case): a kind renders here iff
     (a) it is USER-DOMAIN — its attr namespace is NOT in
         `seon.db/substrate-kinds` (THE shared provenance derivation:
         a kind is substrate iff its `:seon.schema/key` row is a
         bootstrap row — the SAME rule `seon.db/store-inventory`
         orders and splits by, so an agent-minted kind qualifies
         whatever its keyword spelling), and
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
    [clojure.set :as cset]
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.schema :as schema]))

(def kind-render-cap
  "Per-kind rendered-chars BACKSTOP, not a working limit — findings
   are expected to fit whole far below it (same stance as
   `seon.ctx/substrate-eval-render-cap`). Over it, the kind's tail is
   replaced by a loud marker carrying the query that reads the
   complete rows."
  20000)

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
  (let [sub (db/substrate-kinds db)]
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

;; ============================================================
;; The question-adjacent pointer (`:findings-pointer`, priority 95 —
;; after :turns at 90, before :prompt at 99). The L12 finding
;; (opus-live-tests 2026-06-12 addendum 3): a stored finding rendered
;; IN FULL in <findings> — ~2.2k lines into a ~125k-char prompt — and
;; the agent STILL grepped the repo for an answer the section already
;; held. Not a retrieval gap, a QUESTION-ADJACENT-BINDING gap. The fix
;; is a one-to-three-line relevance pointer rendered near the prompt
;; tail: which stored kinds share distinctive terms with the agent's
;; open question. STRUCTURAL — term overlap only, no scenario's terms
;; special-cased anywhere; scales as findings grow (relocating the
;; whole section would not).
;; ============================================================

(def pointer-min-term-len
  "Minimum token length to count as DISTINCTIVE. Short tokens
   (is/are/db/the/fn) are overwhelmingly structural English or
   ubiquitous code fragments; 4+ keeps identifiers
   (validate-values!, transact, entity) and drops the chaff. The
   SIMPLE deterministic scoring choice (vs inverse-frequency
   weighting): count of shared len>=4 tokens — zero state, zero
   tuning, byte-stable per db value."
  4)

(def pointer-min-shared
  "Minimum SHARED distinctive tokens for a finding row to clear the
   pointer threshold. One shared token is coincidence; two is a
   relation worth a line."
  2)

(def pointer-max-rows
  "Pointer covers at most the top N matching rows — it is a POINTER,
   not a second findings render."
  3)

(def pointer-stopwords
  "Tokens never counted as distinctive: articles + pronouns/pro-forms
   (incl. the wh- words) ONLY. Deliberately NO domain or content
   words — dropping those would be answer-shaping by omission. Most
   structural English (is/and/for/not/…) already falls to
   [[pointer-min-term-len]]."
  #{"the" "a" "an"
    "i" "me" "my" "mine" "myself" "you" "your" "yours" "yourself"
    "we" "us" "our" "ours" "ourselves" "he" "him" "his" "himself"
    "she" "her" "hers" "herself" "it" "its" "itself"
    "they" "them" "their" "theirs" "themselves"
    "this" "that" "these" "those"
    "what" "which" "who" "whom" "whose"
    "where" "when" "how" "why" "there" "here"})

(schema/register! ::text :string)
(schema/register! ::terms [:set :string])

(defn terms
  "The distinctive-term set of `s`: lowercase, tokenized so code-ish
   tokens survive intact (`validate-values!`, `seon.db/query`,
   `:my.kb/claim` — the token charset keeps !?*+=<>_./- inside a
   token; surrounding prose punctuation splits), stripped of
   leading/trailing ./-_: (so a sentence-ending `transact.` equals
   `transact` and a keyword's colon drops), minus
   [[pointer-stopwords]], minimum [[pointer-min-term-len]] chars."
  {:malli/schema [:=> [:catn [::text ::text]] ::terms]}
  [s]
  (->> (re-seq #"[a-z0-9!?*+=<>_./:-]+" (str/lower-case s))
       (map #(str/replace % #"^[./:_-]+|[./:_-]+$" ""))
       (remove pointer-stopwords)
       (filter #(>= (count %) pointer-min-term-len))
       set))

(defn- row-terms
  "[[terms]] over every string value of a pulled row
   (cardinality-many string values count element-wise) — the same
   readable content [[string-content?]] gates on, all attrs included
   (provenance paths overlap questions that mention files)."
  [row]
  (transduce (comp (mapcat (fn [v]
                             (cond
                               (string? v)     [v]
                               (sequential? v) (filter string? v)
                               :else           nil)))
                   (map terms))
             cset/union #{} (vals row)))

(schema/register! ::shared-terms [:vector :string])
(schema/register! ::pointer-match
  [:map
   [:seon.db/kind :seon.db/kind]
   [::shared-terms ::shared-terms]])

(defn question-matches
  "The finding rows of `kinds` (a [[user-domain-kinds]] result) whose
   string content shares at least [[pointer-min-shared]] distinctive
   terms with `question` — top [[pointer-max-rows]] by shared-term
   count (ties broken by kind name then terms — deterministic), each
   as {:seon.db/kind k ::shared-terms [sorted …]}. Pure: no db, no
   special-casing of any term."
  {:malli/schema [:=> [:catn [::question ::text]
                       [::kinds [:vector ::kind-entry]]]
                  [:vector ::pointer-match]]}
  [question kinds]
  (let [q (terms question)]
    (if (empty? q)
      []
      (->> (for [[kind _attrs rows] kinds
                 row rows
                 :let [shared (cset/intersection q (row-terms row))]
                 :when (>= (count shared) pointer-min-shared)]
             {:seon.db/kind kind ::shared-terms (vec (sort shared))})
           distinct
           (sort-by (juxt #(- (count (::shared-terms %)))
                          (comp str :seon.db/kind)
                          ::shared-terms))
           (take pointer-max-rows)
           vec))))

(defn findings-pointer-block
  "The `<findings-pointer>` block for `agent-id` in db value `db`: one
   line per matched kind naming the ACTUAL shared terms and the
   read-back query, pointing the agent at the full rows already
   rendered in `<findings>` above. \"\" when the agent is idle (no
   task in progress — `seon.ctx/task-in-progress?`, the same MID-TASK
   gate as `seon.agent.turns/turns-block`; the per-turn self-fold does
   NOT close it, so the pointer persists through a research wake —
   opus-live-tests 2026-06-12 finding 1), when the store holds no
   user-domain content, or when no row clears [[pointer-min-shared]] —
   reactive-context: derived per render, vanishes by itself. The
   question text is the MOST RECENT live inbound message
   (`seon.ctx/latest-inbound-text`), regardless of fold state."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val] [::agent-id :string]]
                  :string]}
  [db agent-id]
  (let [input    {:seon.agent/id agent-id :seon.db/db db}
        question (if (ctx/task-in-progress? input)
                   (ctx/latest-inbound-text input)
                   "")]
    (if (str/blank? question)
      ""
      (let [kinds   (user-domain-kinds db)
            matches (question-matches question kinds)]
        (if (empty? matches)
          ""
          (let [attrs-of (into {} (map (fn [[k a _]] [k a]) kinds))
                grouped  (reduce (fn [acc {k :seon.db/kind
                                           ts ::shared-terms}]
                                   (update acc k (fnil into (sorted-set))
                                           ts))
                                 (sorted-map) matches)]
            (str "<findings-pointer>\n"
                 (str/join "\n"
                   (for [[kind ts] grouped]
                     (str "Stored findings overlap your question — " kind
                          " (terms: " (str/join ", " ts) "). Full rows"
                          " are in <findings> above — consult them"
                          " BEFORE researching; re-read: "
                          (read-query-hint (attrs-of kind)))))
                 "\n</findings-pointer>")))))))

(defn findings-pointer-section
  "Context-section fn (`:findings-pointer`, substrate-default-ctx
   priority 95): [[findings-pointer-block]] for the CALLING agent —
   absent `:seon.db/db` defaults to the current conn, the same
   convention as every sibling section fn."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (findings-pointer-block (or db @db/*conn*) id))
