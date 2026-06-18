(ns seon.agent.findings
  "Stored findings as CONTEXT — the question-adjacency POINTER rung
   (`:findings-pointer`, core-default-ctx priority 95). It does NOT
   render row content; rows reach the agent via the agent's OWN query.
   `seon.db/store-inventory` teaches what EXISTS (attr names + counts);
   the `<inventory>` section surfaces that. When a stored finding is
   relevant to the open question, this pointer names the kind + the
   shared distinctive terms + the read-back query, so the agent reads
   the rows by QUERYING rather than from a raw dump in the prompt.

   STRUCTURAL, never a name-list (uniformity rule — one mechanism, no
   `:my.kb` special case): a kind is POINTER-ELIGIBLE iff
     (a) it is USER-DOMAIN — its attr namespace is NOT in
         `seon.db/core-kinds` (THE shared provenance derivation:
         a kind is core iff its `:seon.schema/key` row is a
         bootstrap row — the SAME rule `seon.db/store-inventory`
         orders and splits by, so an agent-minted kind qualifies
         whatever its keyword spelling), and
     (b) it has READABLE CONTENT — at least one live string value
         under one of its own attrs (pure-numeric tally kinds carry
         nothing to read; querying them is taught elsewhere).

   Reactive-context principle: a pure function of the render's db
   value. A kind becomes pointer-eligible the render after its rows are
   transacted and drops out the render after they are retracted —
   nothing stored, nothing to acknowledge. `user-domain-kinds` carries
   no `:seon.agent/id` filter, deliberately: every agent's stored
   findings are visible to every agent (cross-agent accumulation is the
   point); the pointer itself is gated by the CALLING agent's open
   question."
  (:require
    [clojure.set :as cset]
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.schema :as schema]))

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

;; The pointer-eligible-kind entry shape — shared by
;; [[question-matches]] (the agent's `:findings-pointer` context rung)
;; and the inspector's findings pane (`seon.web.inspector`), so the
;; dashboard and the prompt read ONE derivation (shared-shape rule — no
;; twin query). `:seon.db/kind` / `:seon.db/attrs` are
;; `store-inventory`'s own registered shapes, referenced, not
;; re-inlined.
(schema/register! ::rows [:vector :map])
(schema/register! ::kind-entry
  [:tuple :seon.db/kind :seon.db/attrs ::rows])

(defn user-domain-kinds
  "The pointer-eligible kinds of db value `db` — `[[kind attrs rows]
   …]`, kind-name order (deterministic). Selection rules (a) + (b) from
   the ns docstring. PUBLIC: the inspector findings pane derives its
   per-kind summary from this same fn (one truth for 'what has this
   cluster learned' — prompt and dashboard can never disagree)."
  {:malli/schema [:=> [:catn [::db :seon.db/db-val]]
                  [:vector ::kind-entry]]}
  [db]
  (let [sub (db/core-kinds db)]
    (->> (db/store-inventory {:seon.db/db db})
         (remove #(contains? sub (:seon.db/kind %)))
         (keep (fn [{:seon.db/keys [kind attrs]}]
                 (let [rows (kind-rows db (vec (keys attrs)))]
                   (when (string-content? kind rows)
                     [kind attrs rows]))))
         (sort-by (comp str first))
         vec)))

(defn- read-query-hint
  "The copy-paste query reading a kind's complete rows, anchored on
   its most-populated attr (covers the most rows when rows are
   sparse)."
  [attrs]
  (str "(seon.db/query '[:find (pull ?e [*]) :where [?e "
       (key (apply max-key val attrs)) " _]])"))

;; ============================================================
;; The question-adjacent pointer (`:findings-pointer`, priority 95 —
;; after :turns at 90, before :prompt at 99) — the SOLE findings
;; surface in the prompt. The L12 finding (opus-live-tests 2026-06-12
;; addendum 3): a stored finding rendered IN FULL ~2.2k lines into a
;; ~125k-char prompt and the agent STILL grepped the repo for an answer
;; that dump already held. Not a retrieval gap, a
;; QUESTION-ADJACENT-BINDING gap — AND that full dump cost ~8.7k chars
;; duplicating the transcript. So the raw dump is gone; what remains is
;; a one-to-three-line relevance pointer rendered near the prompt tail:
;; which stored kinds share distinctive terms with the agent's open
;; question, plus the query that reads their rows. STRUCTURAL — term
;; overlap only, no scenario's terms special-cased anywhere; scales as
;; findings grow.
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
   read-back query that reads the full rows (the agent reads them by
   QUERYING — there is no row dump in the prompt). \"\" when the agent
   is idle (no
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
                          " (terms: " (str/join ", " ts) "). Read them"
                          " BEFORE researching: "
                          (read-query-hint (attrs-of kind)))))
                 "\n</findings-pointer>")))))))

(defn findings-pointer-section
  "Context-section fn (`:findings-pointer`, core-default-ctx
   priority 95): [[findings-pointer-block]] for the CALLING agent —
   absent `:seon.db/db` defaults to the current conn, the same
   convention as every sibling section fn."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.db/keys [db] :seon.agent/keys [id]}]
  (findings-pointer-block (or db @db/*conn*) id))
