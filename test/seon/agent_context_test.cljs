(ns seon.agent-context-test
  "Guard tests for the context-render refactor (context-refactor-spec
   2026-06-08) + the api-discoverability fix (capabilities-section).
   These pin the invariants that the prior live bugs violated:

     (a) an agent with NO stored `:seon.agent/ctx` still gets the FULL
         default context — the regression that started this. Context is
         a pure function of the DB with a CODE-default section layout,
         never empty just because no `:seon.agent/ctx` was seeded.
     (b) agent-path (`render-prompt`) ≡ inspector-path
         (`inspect/ctx-preview`) ≡ the would-be persisted
         `:seon.turn/prompt-text` for the same (db,id) — ONE composer,
         divergence impossible.
     (c) each section fn renders non-blank given seeded data (would have
         caught a section silently returning \"\" when data IS present).
     (d) the composed context contains the section markers when the
         underlying sections have content; the transcript interleaves
         messages + evals chronologically (not block-after-block).
     (e) bounded-context guard — a single eval with a multi-MB result
         does NOT blow the agent's context; `transcript-section`
         (and therefore `render-prompt`) stays under a sane bound. This
         is the context-SAFETY invariant: no single entity may dominate
         the context.
     (f) capabilities-section — the `## What you can do` worked-examples
         block is in the default ctx, renders the map-in call shapes, and
         is DERIVED from the persisted core `:seon.fn` arglists (not a
         hardcoded blob).

   All tests open a FRESH `:memory` datahike conn (via
   `seon.client/open-agent-conn!`, the same boot helper the pod uses)
   and seed an agent + session + turns + messages + evals directly — so
   nothing here touches the live agent.

   Run interactively via MCP eval:
     (require 'seon.agent-context-test :reload)
     (cljs.test/run-tests 'seon.agent-context-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is testing async]]
    [datahike.api :as d]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.inspect :as inspect]))

;; ---------------------------------------------------------------------------
;; Fixture — a fresh conn seeded with one agent + session + turns. Returns a
;; Promise of the conn so tests can chain. `db/*conn*` is bound for the extent
;; of `body` (a 0-arg fn that may itself return a Promise) because several
;; section fns (current-session / messages / evals / current-ns) reach
;; `@db/*conn*` directly rather than the `:seon.db/db` in their input.
;; ---------------------------------------------------------------------------

(def ^:private agent-id "AGTctxtest0001")        ; 14 chars (:seon.db/id)

(defn- seed-tx
  "tx-data for an agent with NO `:seon.agent/ctx`, a session with two
   turns. Turn 1: a user message + a FAILED eval (drives warnings).
   Turn 2: a successful eval in ns `:seon.agent.ctxtest` plus a
   `:seon.ns`/`:seon.fn` for that ns (drives current-ns). `extra-evals`
   lets a test append big-result evals to turn 2.

   The introspection-indexed core-fn `:seon.ns`/`:seon.fn` rows
   (`seon.client/index-substrate!`) are appended INTO the same tx-vector —
   the SAME data the pod seeds at boot — so `capabilities-section` has
   the persisted entities it derives the `## What you can do` block from
   (no parallel hardcoded fixture)."
  [extra-evals]
  (let [now (js/Date.)
        t   (fn [ms] (js/Date. (+ (.getTime now) ms)))]
    (into
      [{:seon.agent/id agent-id
        :seon.agent/state :idle
        :seon.agent/sessions
        [{:seon.session/id "SESctxtest0001"
          :seon.session/at (t 0)
          :seon.session/turns
          [{:seon.turn/id "TRNctxtest0001"
            :seon.turn/at (t 10)
            :seon.turn/status :done
            :seon.turn/messages
            [{:seon.message/id "MSGctxtest0001"
              :seon.message/role :user
              :seon.message/content "build me a thing"
              :seon.message/agent [:seon.agent/id agent-id]
              :seon.message/at (t 11)}
             {:seon.message/id "MSGctxtest0002"
              :seon.message/role :assistant
              :seon.message/content "on it"
              :seon.message/agent [:seon.agent/id agent-id]
              :seon.message/at (t 12)}]
            :seon.turn/evals
            [{:seon.eval/id "EVLctxtestF001"
              :seon.eval/at (t 13)
              :seon.eval/duration-ms 5
              :seon.eval/source "(seon.db/query [:bad])"
              :seon.eval/ok? false
              :seon.eval/error "boom — bad query"
              :seon.eval/ns :seon.agent.ctxtest}]}
           {:seon.turn/id "TRNctxtest0002"
            :seon.turn/at (t 20)
            :seon.turn/status :done
            :seon.turn/evals
            (into [{:seon.eval/id "EVLctxtestK001"
                    :seon.eval/at (t 21)
                    :seon.eval/duration-ms 3
                    :seon.eval/source "(defn greet [] :hi)"
                    :seon.eval/ok? true
                    :seon.eval/result-edn "#'seon.agent.ctxtest/greet"
                    :seon.eval/ns :seon.agent.ctxtest}]
                  extra-evals)}]}]}
       ;; Program-graph entities for the agent's current ns so
       ;; namespace-context-section has source to render.
       {:seon.ns/name :seon.agent.ctxtest
        :seon.ns/source "(ns seon.agent.ctxtest)"}
       {:seon.fn/sym "seon.agent.ctxtest/greet"
        :seon.fn/ns [:seon.ns/name :seon.agent.ctxtest]
        :seon.fn/source "(defn greet [] :hi)"}]
      ;; the introspection-indexed core-fn :seon.ns + :seon.fn rows
      ;; (drives capabilities) — the SAME data the pod seeds at boot
      (client/index-substrate!))))

(defn- with-seeded-conn
  "Open a fresh conn, seed it (optionally with `extra-evals` on turn 2),
   and run `body` (1-arg `conn`) with `db/*conn*` bound for the SYNC
   extent of `body`. `body`'s assertions must be synchronous: a plain
   `binding` does NOT survive Promise `.then` boundaries in CLJS (unlike
   the ALS-backed `db/with-agent`), so we rebind `db/*conn*` right
   around the synchronous `body` call. Returns a Promise."
  ([body] (with-seeded-conn [] body))
  ([extra-evals body]
   (-> (client/open-agent-conn!)
       (.then (fn [conn]
                ;; transact under the binding so tx-context defaults resolve,
                ;; then re-establish it around the synchronous body call.
                (binding [db/*conn* conn]
                  (-> (db/transact! {:seon.db/tx-data (seed-tx extra-evals)})
                      (.then (fn [_]
                               (binding [db/*conn* conn]
                                 (body conn)))))))))))

(defn- big-eval
  "A turn-2 eval whose `:seon.eval/result-edn` is `n` chars long — the
   shape of the live 9.7M-char `pull` blob that blew the context."
  [n]
  {:seon.eval/id "EVLctxtestBIG1"
   :seon.eval/at (js/Date. (+ (.getTime (js/Date.)) 30))
   :seon.eval/duration-ms 9
   :seon.eval/source "(seon.db/pull {:seon.db/pull-pattern '[*]})"
   :seon.eval/ok? true
   :seon.eval/result-edn (apply str (repeat n "x"))
   :seon.eval/ns :seon.agent.ctxtest})

;; ---------------------------------------------------------------------------
;; (a) THE regression — no stored ctx still yields the full default context.
;; ---------------------------------------------------------------------------

(deftest no-stored-ctx-still-gets-full-default-context
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db @conn
                  {:seon.render/keys [text sections]}
                  (agent/assemble-context {:seon.db/db db :seon.agent/id agent-id})]
              (is (pos? (count text))
                  "no :seon.agent/ctx → STILL non-empty (code default, not 0)")
              (is (= [:system :capabilities :namespace-context
                      :warnings :transcript :prompt]
                     sections)
                  "the substrate-default section names, in order
                   (static→dynamic): system, capabilities, namespace-context,
                   warnings, transcript, prompt"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (b) agent-path ≡ inspector-path ≡ would-be persisted prompt-text.
;; ---------------------------------------------------------------------------

;; The system section embeds `(js/Date.)` as a `Now:` line, so two
;; renders microseconds apart differ ONLY on that wall-clock line.
;; Normalize it away before comparing — everything else is a pure
;; function of the DB and must be byte-identical across the three paths.
(defn- strip-now [s]
  (str/replace s #"\n  Now: [^\n]*\n" "\n  Now: <NORMALIZED>\n"))

(deftest agent-inspector-and-prompt-text-agree
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db          @conn
                  ;; render-prompt is what run-turn! persists as
                  ;; :seon.turn/prompt-text — assert that exact source.
                  agent-text  (strip-now (agent/render-prompt agent-id))
                  composer    (strip-now
                                (:seon.render/text
                                  (agent/assemble-context
                                    {:seon.db/db db :seon.agent/id agent-id})))
                  inspector   (strip-now
                                (:seon.render/text
                                  (inspect/ctx-preview {:seon.agent/id agent-id})))]
              (is (pos? (count agent-text)) "agent path non-empty")
              (is (= agent-text composer)
                  "render-prompt == assemble-context (same composer)")
              (is (= agent-text inspector)
                  "inspector left-pane text == agent prompt text — no divergence"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (c) Each section fn renders non-blank given seeded data.
;; ---------------------------------------------------------------------------

(deftest each-section-renders-non-blank
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}]
              (is (not (str/blank? (agent/system-section input))) "system")
              (is (not (str/blank? (agent/capabilities-section input)))
                  "capabilities — non-blank because core :seon.fn rows are seeded")
              (is (not (str/blank? (agent/namespace-context-section input)))
                  "namespace-context — non-blank because a :seon.ns + :seon.fn exist")
              (is (not (str/blank? (agent/warnings-section input)))
                  "warnings — the seeded failed eval surfaces")
              (is (not (str/blank? (agent/transcript-section input)))
                  "transcript — seeded messages + evals")
              (is (not (str/blank? (agent/prompt-section input))) "prompt"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d) Composed context contains the section markers when sections have content.
;; ---------------------------------------------------------------------------

(deftest composed-context-includes-non-blank-section-markers
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (str/includes? text "<system") "system marker present")
              (is (str/includes? text "<transcript>") "transcript marker present")
              (is (str/includes? text "<namespace-context>")
                  "namespace-context marker present")
              (is (str/includes? text "<warnings>") "warnings marker present"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (d2) transcript-section interleaves messages + evals chronologically.
;;      Seed time order: user msg (t11) → assistant msg (t12) → failed eval
;;      (t13) → successful eval (t21). The merged stream must preserve that
;;      order — a user message BEFORE its eval, an eval AFTER the message
;;      that prompted it — proving the two streams are merged by :at, not
;;      concatenated block-after-block.
;; ---------------------------------------------------------------------------

(deftest transcript-interleaves-messages-and-evals-chronologically
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  ts   (agent/transcript-section
                         {:seon.db/db db :seon.agent/id agent-id})
                  i-user      (str/index-of ts "user> build me a thing")
                  i-assistant (str/index-of ts "assistant> on it")
                  i-failed    (str/index-of ts "boom — bad query")
                  i-success   (str/index-of ts "seon.agent.ctxtest/greet")]
              (is (str/includes? ts "<transcript>") "transcript marker present")
              (is (and i-user i-assistant i-failed i-success)
                  "all four transcript items present (2 msgs + 2 evals)")
              ;; chronological: user msg < assistant msg < failed eval < success eval
              (is (< i-user i-assistant)
                  "user message before assistant message")
              (is (< i-assistant i-failed)
                  "messages interleaved BEFORE the eval that followed them
                   (proves merge by :at, not message-block-then-eval-block)")
              (is (< i-failed i-success)
                  "failed eval (t13) before successful eval (t21)"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (f) capabilities-section — the "## What you can do" worked-examples block.
;;     Guards the api-discoverability bug: the section must be in the default
;;     ctx, render the map-in call shapes, and be DERIVED from the persisted
;;     core :seon.fn arglists (not a hardcoded blob).
;; ---------------------------------------------------------------------------

(deftest substrate-default-ctx-includes-capabilities-after-system
  (let [names (mapv :seon.ctx/name (agent/substrate-default-ctx))]
    (is (some #{:capabilities} names)
        "substrate-default-ctx contains the :capabilities section")
    (is (= [:system :capabilities]
           (vec (take 2 names)))
        ":capabilities renders right after :system")
    (is (not (contains? (set names) :root-pull))
        "no :root-pull section in the default layout — the amplifier is gone")))

;; ---------------------------------------------------------------------------
;; root-pull is DELETED — no fn, no advertisement, no default section.
;; ---------------------------------------------------------------------------

(deftest root-pull-is-deleted
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/assemble-context
                           {:seon.db/db db :seon.agent/id agent-id}))]
              (is (not (str/includes? text "root-pull"))
                  "no system-section advertisement of root-pull in context"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest capabilities-section-renders-worked-call-shapes
  (async done
    (-> (with-seeded-conn
          (fn [conn]
            (let [db    @conn
                  input {:seon.db/db db :seon.agent/id agent-id}
                  cap   (agent/capabilities-section input)
                  ;; the WHOLE assembled context, not just the section,
                  ;; so we prove the worked shapes reach the agent.
                  full  (:seon.render/text
                          (agent/assemble-context
                            {:seon.db/db db :seon.agent/id agent-id}))]
              (is (not (str/blank? cap)) "capabilities-section non-blank")
              (is (str/includes? full "## What you can do")
                  "the promised heading is present in assembled context")
              ;; map-in shapes — the exact mistakes we observed live.
              (is (str/includes? full ":seon.db/tx-data")
                  "transact! map-in key shown — positional call impossible")
              (is (str/includes? full "(seon.db/query {")
                  "query shown as map-in, not positional")
              (is (str/includes? full "(seon.db/current-agent-id)")
                  "current-agent-id shown — no seon.agent/current-agent-id guess")
              ;; DERIVED from persisted :seon.fn arglists, not hardcoded:
              ;; the rendered text must contain the REAL arglist string from
              ;; the seeded entity. index-substrate! reads arglists from the
              ;; actual source; since T15 gave `transact!`/`query` two call
              ;; shapes (map-in + datahike-positional) they introspect as the
              ;; variadic `([& call-args])` / `([& args])` dispatchers —
              ;; proving the rendered shapes are derived from real source, not
              ;; a curated fiction.
              (is (str/includes? cap "(seon.db/transact! ([& call-args]))")
                  "transact! arglist is the REAL ([& call-args]) from introspected source")
              (is (str/includes? cap "(seon.db/query ([& args]))")
                  "query arglist is the REAL ([& args]) from introspected source")
              ;; bounded — the section is the curated core API only, not a dump.
              (is (< (count cap) 4000)
                  (str "capabilities-section bounded — got " (count cap))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; (e) Bounded-context guard — one huge eval result does NOT blow context.
;; ---------------------------------------------------------------------------

(deftest huge-eval-result-does-not-blow-context
  (async done
    (let [big-n 5000000]                            ; 5 MB result
      (-> (with-seeded-conn
            [(big-eval big-n)]
            (fn [conn]
              (let [db    @conn
                    ts    (agent/transcript-section
                            {:seon.db/db db :seon.agent/id agent-id})
                    full  (agent/render-prompt agent-id)
                    ;; Comfortable ceiling: a handful of capped rows +
                    ;; the other sections. Far below the 5 MB blob.
                    ceil  50000]
                (is (< (count ts) ceil)
                    (str "transcript bounded despite " big-n
                         "-char result — got " (count ts)))
                (is (< (count full) ceil)
                    (str "render-prompt bounded — got " (count full)))
                (is (str/includes? ts "chars elided⟩")
                    "the big result was elided with the marker"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
