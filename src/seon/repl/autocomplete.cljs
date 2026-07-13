(ns seon.repl.autocomplete
  "REPL autocomplete — byte-exact situation context + turn-mining export.

   The repl-autosuggest lane (docs/prds/repl-autosuggest/design.md): a tiny
   encoder-decoder learns the mapping from a compact SITUATION projection to
   the next REPL forms. This ns owns the seon side of that contract:

     - [[context]] — the encoder input. NOT a new renderer: the ONE prompt
       producer (`seon.agent.ctx/render-context`, the same fn behind
       `render-prompt` and the web UI) invoked with the `:autocomplete`
       render PROFILE (db-stored `:seon.config/context-profiles`, code
       default [[context-blocks]]) — the existing :plan / :transcript
       section fns under tight per-block token caps. Pure over a db VALUE:
       the live db at inference, `(db/as-of db t)` at export — same
       function, same bytes, by construction.
     - [[rate!]] + the `::rating`/`::tag` curation attrs — mark turns
       gold/good/excluded for the training corpus.
     - [[export!]] — walk every agent's turns (`seon.agent.ctx/agent-turns`),
       render [[context]] at each turn's `:seon.agent.turn/rendered-as-of`,
       pair it with the turn's ok `:seon.eval/source` forms, and write one
       JSONL row per turn under `data/tune/`.

   If this projection (or any block it renders) changes, previously exported
   datasets are STALE — re-export, never patch (each row stamps the git sha)."
  (:require
    ["node:child_process" :as child-process]
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [clojure.string :as str]
    ;; The profile's section fns resolve LATE (symbol slots via
    ;; seon.eval/lookup-value) — required here so every bundle that carries
    ;; this ns also carries the blocks it renders.
    [my.plan.internal]
    [seon.agent.ctx :as ctx]
    [seon.agent.ctx.namespaces :as ns-cards]
    [seon.agent.ctx.transcript]
    [seon.agent.home :as home]
    [seon.ai.tokens :as tokens]
    [seon.config :as config]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ============================================================
;; Curation attrs — datoms ON the turn entity (`:seon.agent.turn/id` is
;; the transactable handle). Genuine judgments, stored with tx-meta
;; provenance like every other write; training weights :gold higher and
;; [[export!]] drops :excluded rows.
;; ============================================================

(schema/register! ::rating [:enum :gold :good :excluded])
(schema/register! ::tag    [:vector :keyword])

(schema/register! ::ok?    :boolean)
(schema/register! ::error  :string)

(schema/register! ::rate-request
  [:map
   [:seon.agent.turn/id :seon.agent.turn/id]
   [::rating ::rating]
   [::tag {:optional true} ::tag]])

(schema/register! ::rate-response
  [:map
   [::ok? ::ok?]
   [:seon.agent.turn/id {:optional true} :seon.agent.turn/id]
   [::error {:optional true} ::error]])

(defn ^:async rate!
  "Rate a turn for the autocomplete corpus (:gold | :good | :excluded).

   Upserts `::rating` (+ optional `::tag` keywords) onto the turn addressed
   by `:seon.agent.turn/id`. An unknown id is refused as a value (no blind
   upsert minting a hollow turn). → `{::ok? true …}` or a fail envelope."
  {:malli/schema [:=> [:cat ::rate-request] ::rate-response]}
  [{turn-id :seon.agent.turn/id rating ::rating tags ::tag}]
  (let [turn (db/entity-lazy {:seon.db/ref [:seon.agent.turn/id turn-id]})]
    (if (nil? (:seon.agent.turn/id turn))
      {::ok? false ::error (str "rate!: no turn " (pr-str turn-id)
                                " — pass a real :seon.agent.turn/id")}
      (let [res (await (db/transact!
                         {:seon.db/tx-data
                          [(cond-> {:seon.agent.turn/id turn-id
                                    ::rating            rating}
                             (seq tags) (assoc ::tag (vec tags)))]}))]
        (if (:seon.db/ok? res)
          {::ok? true :seon.agent.turn/id turn-id}
          {::ok? false ::error (str (:seon.db/error res))})))))

;; ============================================================
;; The projection PROFILE — a block list in the exact `:seon.agent/ctx`
;; manifest shape, rendered by the ONE producer. Total budget ≈ 700 BPE
;; tokens (the needle encoder envelope is 1024; retrieved cards ride
;; separately in the JSONL row).
;; ============================================================

(def context-blocks
  "The default `:autocomplete` render profile — existing blocks, tight caps.

   Reuses the registered section fns verbatim (never a second renderer):
   `:plan` (current position — the windowed plan bands) and `:transcript`
   configured as the RECENT TAIL: the masthead (current-ns line), the last
   turn's events with result heads decayed to stubs, the newest unanswered
   inbound message. Every process/wall-clock byte is OFF: no readline (the
   one live-`now` line), no `result/<id>` handles or resume markers (the
   process-identity bytes) — the render is a pure function of the db VALUE.
   The `:warnings` derivation is deliberately EXCLUDED (its slow-evals check
   reads the wall clock and canvas checks read live vars — impure over a db
   value). Per-block `:seon.agent.ctx/token-cap` bounds each band. The
   CODE default for the config-through-DB `:seon.config/context-profiles`
   `:autocomplete` entry — a db-stored profile (the one in force at an
   as-of t) always wins ([[context]])."
  [{:seon.agent.ctx/name      :plan
    :seon.agent.ctx/priority  45
    :seon.agent.ctx/token-cap 200
    :seon.render/ai           'my.plan.internal/plan-block}
   {:seon.agent.ctx/name      :transcript
    :seon.agent.ctx/priority  100
    :seon.agent.ctx/token-cap 440
    :seon.agent.ctx/cap-keep  :tail
    :seon.render/ai           'seon.agent.ctx.transcript/transcript-block
    ;; recent tail only: last turn verbatim, older evals evicted (a
    ;; zero-budget tier covers every older offset); messages always render
    ;; but the block token-cap bounds the total, keeping the newest tail.
    :seon.agent.ctx.transcript/turns-retained 1
    :seon.agent.ctx.transcript/tiers
    [{:seon.agent.ctx.transcript/from-turn 1
      :seon.agent.ctx.transcript/token-cap 0}]
    ;; result HEADS: every result body clips to a 48-token stub.
    :seon.agent.ctx.transcript/result-decay
    [{:seon.agent.ctx.transcript/from-turn-offset 0
      :seon.agent.ctx.transcript/token-cap 48}]
    :seon.agent.ctx.transcript/readline? false
    :seon.agent.ctx.transcript/result-handles? false
    ;; PINNED as a block constant (the schema default's value): the
    ;; converters then never fall back to their live-conn read — the
    ;; render stays a pure fn of the db value.
    :seon.agent.ctx/escape-clipping? true}])

(defn- profile-from-db
  "The `:autocomplete` entry of the db-stored context profiles, or nil.

   Reads `:seon.config/context-profiles` off the `:seon.config` singleton
   IN the passed db VALUE (never the live conn / config-view) — an as-of
   render regenerates under the profile in force at that t. nil when the
   store predates the attr or carries no `:autocomplete` profile."
  [db]
  (when (and db (contains? (db/installed-schema db)
                           :seon.config/context-profiles))
    (some->> (:seon.config/context-profiles
               (db/entity {:seon.db/db db
                           :seon.db/ref [:seon.config/id
                                         config/cluster-config-id]}))
             (db/decode-edn-value :seon.config/context-profiles)
             :autocomplete
             seq
             vec)))

(schema/register! ::context-request
  [:map
   [:seon.agent/id :string]
   [:seon.db/db {:optional true} :seon.db/db]])

(defn context
  "The autocomplete situation projection for one agent, as a bare String.

   THE encoder input (byte-exact contract): the ONE producer
   (`seon.agent.ctx/render-context` — the same fn behind the real prompt
   and the web UI) invoked with the `:autocomplete` render profile — the
   db-stored `:seon.config/context-profiles` entry when the passed db
   carries one (the profile in force at that t), else [[context-blocks]].
   Pure over the db VALUE: pass the live db at inference,
   `(db/as-of db rendered-as-of)` at export — identical bytes for
   identical inputs (readline, `result/<id>` handles, and resume markers —
   the wall-clock / process-identity bytes — are off in this profile).
   Deterministic: no wall clock, no randomness."
  {:malli/schema [:=> [:cat ::context-request] :string]}
  [{id :seon.agent/id db :seon.db/db}]
  (let [db (or db @db/*conn*)]
    (ctx/render-context
      {:seon.agent/id id
       :seon.db/db    db
       :seon.agent.ctx/profile (or (profile-from-db db) context-blocks)})))

;; ============================================================
;; Export internals — mining turns into JSONL training rows.
;; ============================================================

(def ^:private qualified-token-re
  "Qualified-symbol tokens in Clojure source — `alias/name` or
   `full.ns/name`. The lookbehind drops keywords (`:ns/kw`) and
   mid-token tails; quoted symbols stay in (a reference is a use)."
  #"(?<![:A-Za-z0-9*+!?<>=._'-])[A-Za-z][A-Za-z0-9*+!?<>=._'-]*/[A-Za-z*+!?<>=_-][A-Za-z0-9*+!?<>=._'-]*")

(defn- home-alias-maps
  "[aliases refers] for `agent-id`'s home requires.

   `aliases` maps alias-string → ns-string (`\"db\"` → `\"seon.db\"`);
   `refers` maps bare-name → full-sym (`\"complete\"` →
   `\"seon.agent.lifecycle/complete\"`). Derived from
   `seon.agent.home/home-requires-for` — the SAME list that wires the agent's ns."
  [agent-id]
  (reduce (fn [[aliases refers] spec]
            (let [ns-sym (first spec)]
              (case (second spec)
                :as    [(assoc aliases (name (nth spec 2)) (name ns-sym)) refers]
                :refer [aliases
                        (reduce (fn [m r]
                                  (assoc m (name r) (str (name ns-sym) "/" (name r))))
                                refers (nth spec 2))]
                [aliases refers])))
          [{} {}]
          (home/home-requires-for agent-id)))

(defn- indexed-fn-syms
  "The set of every `:seon.fn/sym` string in db value `db`."
  [db]
  (into #{} (map first)
        (db/query {:seon.db/db db
                   :seon.db/query '[:find ?s :where [_ :seon.fn/sym ?s]]})))

(defn- called-syms
  "Full `:seon.fn/sym` strings referenced by `target` source text.

   Best-effort, deterministic: qualified tokens matched directly against
   the indexed fn set, then through the agent's home ALIASES; bare refers
   (`complete`, `wait`) matched by word boundary. Sorted vector."
  [target fn-syms aliases refers]
  (let [qualified (->> (re-seq qualified-token-re target)
                       (keep (fn [tok]
                               (if (contains? fn-syms tok)
                                 tok
                                 (let [[a n] (str/split tok #"/" 2)
                                       full  (some-> (get aliases a) (str "/" n))]
                                   (when (and full (contains? fn-syms full))
                                     full))))))
        referred  (keep (fn [[bare full]]
                          (when (and (contains? fn-syms full)
                                     (re-find (js/RegExp.
                                                (str "(^|[\\s(\\[{,'])"
                                                     (str/replace bare #"[.*+?^${}()|\[\]\\]" "\\$&")
                                                     "($|[\\s)\\]},])"))
                                              target))
                            full))
                        refers)]
    (->> (concat qualified referred) distinct sort vec)))

(defn- fn-card
  "The compact one-line card for fn `sym-str` in db value `db`, or nil.

   ONE mechanism with the `:namespaces` compact cards —
   `seon.agent.ctx.namespaces/compact-fn-head` over the indexed
   `:seon.fn` row."
  [db sym-str]
  (when (db/entity-lazy {:seon.db/db db :seon.db/ref [:seon.fn/sym sym-str]})
    (let [row (db/pull {:seon.db/db db
                        :seon.db/ref [:seon.fn/sym sym-str]
                        :seon.db/pull-pattern '[:seon.fn/sym :seon.fn/arglists
                                                :seon.fn/doc :seon.fn/spec]})]
      (ns-cards/compact-fn-head row))))

(def ^:private keyword-token-re
  "Keyword tokens in Clojure source — `:kw`, `:ns/kw`, `::kw` (the `::`
   form matched literally; its expansion is ns-dependent, so coverage
   checks it as written)."
  #"(?<![:A-Za-z0-9*+!?<>=._'-]):{1,2}[A-Za-z][A-Za-z0-9*+!?<>=._'-]*(?:/[A-Za-z][A-Za-z0-9*+!?<>=._'-]*)?")

(defn- target-identifiers
  "The coverage-checkable identifiers in `target` source text.

   Qualified fn/var symbol tokens, keyword tokens, and short
   whitespace-free string literals (opaque ids — plan ids, agent ids,
   hashes). Deterministic regex extraction, best-effort by design."
  [target]
  (vec (distinct
         (concat (re-seq qualified-token-re target)
                 (re-seq keyword-token-re target)
                 (keep second (re-seq #"\"([^\"\s]{2,40})\"" target))))))

(defn- ingredients-coverage
  "Fraction (0–1, 2 decimals) of `target`'s identifiers present in the
   rendered context+cards `haystack` — the context-GAP measure: a low
   value means the model would have to INVENT identifiers the situation
   never showed it. 1 when the target has no identifiers."
  [target haystack]
  (let [ids (target-identifiers target)]
    (if (empty? ids)
      1
      (-> (count (filter #(str/includes? haystack %) ids))
          (* 100)
          (quot (count ids))
          (/ 100)))))

(defn- distractor-syms
  "`k` deterministic distractor fn syms for a turn (needle-style noise).

   Seeded by the turn id — `(hash (str turn-id \"|\" sym))` orders the
   non-called candidates — so re-export reproduces the same rows byte-for-
   byte. No randomness."
  [fn-syms called turn-id k]
  (->> (remove (set called) (sort fn-syms))
       (sort-by #(hash (str turn-id "|" %)))
       (take k)
       vec))

(defn- git-head-sha
  "The repo's current git HEAD sha (the projection version stamp), or
   \"unknown\" outside a git checkout."
  []
  (try (str/trim (.toString (.execSync child-process "git rev-parse HEAD"
                                       #js {:stdio #js ["ignore" "pipe" "ignore"]})))
       (catch :default _ "unknown")))

(defn- store-name
  "This cluster's store name — the basename of `SEON_CLUSTER_DIR`
   (\"default\" when unset)."
  []
  (let [dir (config/env-string "SEON_CLUSTER_DIR")]
    (if (str/blank? dir) "default" (.basename npath dir))))

(defn- row-json
  "ONE JSONL line for a mined turn — the design.md row shape
   (`context`/`cards`/`target`/`meta`), JSON-stringified."
  [{::keys [context-text cards target turn-id agent-id basis-t store sha
            rating coverage]}]
  (let [meta (js-obj "turn-id" turn-id
                     "agent" agent-id
                     "basis-t" basis-t
                     "store" store
                     "projection-sha" sha
                     "coverage" coverage)]
    (when rating (aset meta "rating" (name rating)))
    (js/JSON.stringify
      (js-obj "context" context-text
              "cards" (into-array cards)
              "target" target
              "meta" meta))))

;; ============================================================
;; The exporter.
;; ============================================================

(schema/register! ::out-path       :string)
(schema/register! ::projection-sha :string)
(schema/register! ::distractors    [:int {:min 0}])
(schema/register! ::count          [:int {:min 0}])
;; [min p50 max] token summary of a row column (Token Reporting rule —
;; sizes are ALWAYS tokens; the full distribution is derivable from the
;; JSONL file itself).
(schema/register! ::token-summary  [:vector :int])

(schema/register! ::export-request
  [:map
   [::out-path       {:optional true} ::out-path]
   [::projection-sha {:optional true} ::projection-sha]
   [::distractors    {:optional true} ::distractors]
   [:seon.db/db      {:optional true} :seon.db/db]])

(schema/register! ::export-response
  [:map
   [::ok?              ::ok?]
   [::out-path         {:optional true} ::out-path]
   [::projection-sha   {:optional true} ::projection-sha]
   [::agents           {:optional true} ::count]
   [::turns-walked     {:optional true} ::count]
   [::rows             {:optional true} ::count]
   [::skipped-no-evals {:optional true} ::count]
   [::skipped-no-basis {:optional true} ::count]
   [::skipped-excluded {:optional true} ::count]
   [::cards-missed     {:optional true} ::count]
   ;; Byte-exactness self-check: every row's context is rendered TWICE
   ;; over the same as-of db value; a mismatch is a determinism bug.
   [::determinism-mismatches {:optional true} ::count]
   [::context-tokens   {:optional true} ::token-summary]
   [::target-tokens    {:optional true} ::token-summary]
   [::error            {:optional true} ::error]])

(defn export!
  "Export every agent's ok-eval turns as autocomplete JSONL training rows.

   Walks agent → runs → turns (`seon.agent.ctx/agent-turns`) over db value
   `:seon.db/db` (default: the live db). A turn contributes one row when it
   has ok `:seon.eval/source` forms AND a `:seon.agent.turn/rendered-as-of`
   basis; turns rated `:excluded` are dropped. Per row:

     context — [[context]] rendered against `(db/as-of db rendered-as-of)`
               (the pre-turn database snapshot the model actually saw)
     target  — the turn's ok eval sources, in order, newline-joined
     cards   — compact fn cards for the fns the target CALLS (direct +
               home-alias + refer resolution) plus `::distractors`
               (default 3) deterministic distractor cards
     meta    — turn-id, agent, basis-t, store, projection-sha (git HEAD
               unless `::projection-sha` is passed), rating when present

   Writes `::out-path` (default `data/tune/<store>-<yyyy-mm-dd>.jsonl`,
   one JSON object per line) via node fs and returns honest counters —
   never throws; a failure comes back as `{::ok? false ::error …}`."
  {:malli/schema [:=> [:cat ::export-request] ::export-response]}
  [{db :seon.db/db ::keys [out-path projection-sha distractors]}]
  (try
    (let [db    (or db @db/*conn*)
          sha   (or projection-sha (git-head-sha))
          k     (or distractors 3)
          store (store-name)
          out   (or out-path
                    (str "data/tune/" store "-"
                         (subs (.toISOString (js/Date.)) 0 10) ".jsonl"))
          agent-ids (->> (db/query {:seon.db/db db
                                    :seon.db/query
                                    '[:find ?id :where [_ :seon.agent/id ?id]]})
                         (map first)
                         sort)
          ;; Attr-presence guards (the installed-schema pattern): an OLD
          ;; store may predate ::rating / rendered-as-of — reading a
          ;; never-installed attr must degrade to absent, never throw.
          installed  (db/installed-schema db)
          rating-ok? (contains? installed ::rating)
          basis-ok?  (contains? installed :seon.agent.turn/rendered-as-of)
          acc   (reduce
                  (fn [acc agent-id]
                    (let [[aliases refers] (home-alias-maps agent-id)]
                      (reduce
                        (fn [acc turn]
                          (let [evals   (sort-by :seon.eval/at
                                                 (:seon.agent.turn/evals turn))
                                sources (->> evals
                                             (filter #(true? (:seon.eval/ok? %)))
                                             (map :seon.eval/source)
                                             (remove str/blank?)
                                             vec)
                                basis   (when basis-ok?
                                          (:seon.agent.turn/rendered-as-of turn))
                                rating  (when rating-ok? (::rating turn))
                                acc     (update acc ::turns-walked inc)]
                            (cond
                              (= :excluded rating)
                              (update acc ::skipped-excluded inc)

                              (empty? sources)
                              (update acc ::skipped-no-evals inc)

                              (nil? basis)
                              (update acc ::skipped-no-basis inc)

                              :else
                              (let [aodb    (db/as-of db basis)
                                    ctext   (context {:seon.agent/id agent-id
                                                      :seon.db/db aodb})
                                    ;; determinism self-check: the SAME as-of
                                    ;; db value must render the SAME bytes.
                                    ctext2  (context {:seon.agent/id agent-id
                                                      :seon.db/db aodb})
                                    target  (str/join "\n" sources)
                                    fn-syms (indexed-fn-syms aodb)
                                    called  (called-syms target fn-syms
                                                         aliases refers)
                                    turn-id (:seon.agent.turn/id turn)
                                    syms    (into called
                                                  (distractor-syms fn-syms called
                                                                   turn-id k))
                                    cards   (vec (keep #(fn-card aodb %) syms))
                                    missed  (- (count syms) (count cards))
                                    ;; context-GAP evidence (never "fixed"
                                    ;; here): how much of the target the
                                    ;; situation actually showed the model.
                                    coverage (ingredients-coverage
                                               target
                                               (str ctext "\n"
                                                    (str/join "\n" cards)))]
                                (-> acc
                                    (update ::rows inc)
                                    (update ::cards-missed + missed)
                                    (update ::determinism-mismatches
                                            + (if (= ctext ctext2) 0 1))
                                    (update ::ctx-tokens conj
                                            (tokens/estimate ctext))
                                    (update ::target-tokens conj
                                            (tokens/estimate target))
                                    (update ::lines conj
                                            (row-json {::context-text ctext
                                                       ::cards        cards
                                                       ::target       target
                                                       ::turn-id      turn-id
                                                       ::agent-id     agent-id
                                                       ::basis-t      basis
                                                       ::store        store
                                                       ::sha          sha
                                                       ::rating       rating
                                                       ::coverage     coverage})))))))
                        acc
                        (ctx/agent-turns agent-id db))))
                  {::turns-walked 0 ::rows 0 ::skipped-no-evals 0
                   ::skipped-no-basis 0 ::skipped-excluded 0
                   ::cards-missed 0 ::determinism-mismatches 0
                   ::ctx-tokens [] ::target-tokens [] ::lines []}
                  agent-ids)
          summary (fn [xs]
                    (if (empty? xs)
                      [0 0 0]
                      (let [s (vec (sort xs))]
                        [(first s) (nth s (quot (count s) 2)) (peek s)])))]
      (.mkdirSync nfs (.dirname npath out) #js {:recursive true})
      (.writeFileSync nfs out (str (str/join "\n" (::lines acc)) "\n"))
      {::ok?              true
       ::out-path         out
       ::projection-sha   sha
       ::agents           (count agent-ids)
       ::turns-walked     (::turns-walked acc)
       ::rows             (::rows acc)
       ::skipped-no-evals (::skipped-no-evals acc)
       ::skipped-no-basis (::skipped-no-basis acc)
       ::skipped-excluded (::skipped-excluded acc)
       ::cards-missed     (::cards-missed acc)
       ::determinism-mismatches (::determinism-mismatches acc)
       ::context-tokens   (summary (::ctx-tokens acc))
       ::target-tokens    (summary (::target-tokens acc))})
    (catch :default e
      {::ok? false ::error (str (ex-message e))})))
