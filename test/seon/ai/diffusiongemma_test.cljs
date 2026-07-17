(ns seon.ai.diffusiongemma-test
  "Offline tests for the DiffusionGemma CONTROL adapter (the RunPod
   async-job worker). NO network, NO GPU — every wire test drives the
   adapter through the injected `seon.ai.diffusiongemma/*fetch*` seam
   (root-set!, like the openai adapter's *fetch*; restored in a
   `.finally`) with a SCRIPTED submit→poll sequence, and `*poll-ms*` is
   set to 0 so the poll loop never waits.

   Covers:
     - request->payload kebab→snake + knob passthrough (pure)
     - normalize-output: the per-mode text field; an in-band *_error
       → the :seon.ai/error envelope (pure)
     - happy path: submit → IN_QUEUE → IN_PROGRESS → COMPLETED, text +
       worker-output parsed
     - a FAILED job → an errors-as-values envelope (NOT transport)
     - a transient 503 on submit then success → seon.retry/with-retry!
       recovers (the cold-start transient inherits the turn loop's retry)

   Run interactively via MCP eval:
     (require 'seon.ai.diffusiongemma-test :reload)
     (cljs.test/run-tests 'seon.ai.diffusiongemma-test)"
  (:require
    [cljs.test :refer [deftest is testing async]]
    [seon.ai :as ai]
    [seon.ai.diffusiongemma :as dg]
    [seon.retry :as retry]))

(defn- resolution
  ([] (resolution {}))
  ([config-row]
   (ai/resolved-config-from-rows
     (merge {:seon.ai/provider :diffusiongemma}
            config-row)
     {})))

(def ^:private worker-resolution
  (resolution {:seon.ai/base-url "ep1"}))

(defn- complete
  ([request] (complete request worker-resolution))
  ([request resolved]
   (dg/complete (assoc request :seon.ai/config-resolution resolved))))

;; ============================================================
;; Env + fetch seam helpers.
;; ============================================================

(defn- with-env
  "Run `body` (0-arg → Promise) with process.env vars set/deleted per
   `settings`; snapshot + restore each touched var after."
  [settings body]
  (let [env   (.. js/process -env)
        saved (into {} (map (fn [[k _]] [k (aget env k)])) settings)]
    (doseq [[k v] settings]
      (if (some? v) (aset env k v) (js-delete env k)))
    (-> (js/Promise.resolve (body))
        (.finally (fn []
                    (doseq [[k _] settings]
                      (let [v (get saved k)]
                        (if (some? v) (aset env k v) (js-delete env k)))))))))

(defn- json-response
  "A js/Response carrying `m` as a JSON body at `status`."
  ([status m] (json-response status m {}))
  ([status m headers]
   (js/Response.
     (.stringify js/JSON (clj->js m))
     #js{:status  status
         :headers (clj->js (merge {"content-type" "application/json"} headers))})))

(defn- scripted-fetch
  "A fetch stub returning `responses` (a vector of js/Response, or
   `(fn [url init])`) in call order, recording each url into `urls`."
  [urls responses]
  (let [n (atom -1)]
    (fn [url init]
      (swap! urls conj url)
      (let [r (nth responses (swap! n inc))]
        (js/Promise.resolve (if (fn? r) (r url init) r))))))

(defn- with-worker
  "Bind a configured worker env + the scripted `stub-fetch` + a 0ms poll
   interval, run `body` (0-arg → Promise), restore everything."
  [stub-fetch body]
  (with-env {"RUNPOD_API_KEY"      "k"
             "SEON_DG_API_KEY_ENV" nil}
    (fn []
      (set! dg/*fetch* stub-fetch)
      (set! dg/*poll-ms* 0)
      (-> (js/Promise.resolve (body))
          (.finally (fn [] (set! dg/*fetch* nil) (set! dg/*poll-ms* 3000)))))))

;; The turn loop's retry predicate, replicated: a transport throw OR a
;; 5xx/429 status is transient (matches seon.agent.turn/llm-retryable?).
(defn- retryable?
  [resp]
  (let [err (:seon.ai/error resp)
        st  (:seon.ai/status err)]
    (boolean (or (:seon.ai/transport? err)
                 (and st (or (= st 429) (>= st 500)))))))

;; ============================================================
;; Pure shape — payload build + output normalize.
;; ============================================================

(deftest request->payload-kebab-snake-and-knobs
  (let [p (dg/request->payload
            {::dg/mode               :clamp-smoke
             ::dg/prompt             "hi"
             ::dg/max-new-tokens     256
             ::dg/entropy-bound      0.5
             ::dg/max-denoising-steps 48
             ::dg/t-max              0.8
             ::dg/t-min              0.4
             ::dg/clamp-text         {"5" "hello"}})]
    (is (= "clamp_smoke" (get p "mode")) "mode keyword → snake_case string")
    (is (= "hi" (get p "prompt")))
    (is (= 256 (get p "max_new_tokens")) "kebab attr → snake_case JSON key")
    (is (= 0.5 (get p "entropy_bound")))
    (is (= 48 (get p "max_denoising_steps")))
    (is (= 0.8 (get p "t_max")))
    (is (= 0.4 (get p "t_min")))
    (is (= {"5" "hello"} (get p "clamp_text")) "clamp-text map rides as-is")
    (is (not (contains? p "suffix")) "absent fields are omitted (optional-is-absent)")))

(deftest normalize-output-generate-text
  (let [resp (dg/normalize-output :generate
                                  {:text "(defn mean [xs] …)" :tok_per_s 512
                                   :completion_tokens 40})]
    (is (= "(defn mean [xs] …)" (:seon.ai/text resp)) "generate text from output.text")
    (is (= 512 (get-in resp [::dg/worker-output :tok_per_s]))
        "the RAW worker output is preserved under ::worker-output")
    (is (nil? (:seon.ai/error resp)))))

(deftest normalize-output-infill-and-clamp-text-fields
  (is (= "mid" (:seon.ai/text (dg/normalize-output :infill {:middle_text "mid"})))
      "infill text from output.middle_text")
  (is (= "done" (:seon.ai/text (dg/normalize-output :clamp-smoke {:completion_text "done"})))
      "clamp-smoke text from output.completion_text")
  (is (= "" (:seon.ai/text (dg/normalize-output :probe {:gpu "A100"})))
      "non-text mode → empty text, output still preserved"))

(deftest normalize-output-in-band-error-is-processing
  (let [resp (dg/normalize-output :generate {:gen_error "CUDA OOM"})]
    (is (= "" (:seon.ai/text resp)))
    (is (some? (:seon.ai/error resp)) "an in-band gen_error surfaces as :seon.ai/error")
    (is (re-find #"CUDA OOM" (:seon.ai/msg (:seon.ai/error resp))))
    (is (not (contains? (:seon.ai/error resp) :seon.ai/transport?))
        "a generation error is a PROCESSING error — never retryable")))

;; ============================================================
;; Wire — submit + poll over the injected fetch.
;; ============================================================

(deftest happy-path-polls-to-completion
  (async done
    (let [urls (atom [])
          fetch (scripted-fetch
                  urls
                  [(json-response 200 {:id "job1" :status "IN_QUEUE"})
                   (json-response 200 {:status "IN_PROGRESS"})
                   (json-response 200 {:status "COMPLETED"
                                       :output {:text "(defn mean [xs] (/ (reduce + xs) (count xs)))"
                                                :tok_per_s 512 :completion_tokens 41}})])]
      (-> (with-worker fetch
            #(complete {::dg/mode :generate ::dg/prompt "write mean"}))
          (.then
            (fn [{:seon.ai/keys [text error] :as resp}]
              (is (nil? error))
              (is (re-find #"defn mean" text) "the COMPLETED output.text is surfaced")
              (is (= 512 (get-in resp [::dg/worker-output :tok_per_s]))
                  "tok_per_s (TOKENS, the worker's own metric) rides worker-output")
              (let [us @urls]
                (is (re-find #"/v2/ep1/run$" (first us)) "submit hits …/v2/{EP}/run")
                (is (= 3 (count us)) "submit + 2 polls (IN_PROGRESS then COMPLETED)")
                (is (re-find #"/status/job1$" (last us)) "polls …/status/{job-id}"))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest failed-job-is-errors-as-values
  (async done
    (let [urls (atom [])
          fetch (scripted-fetch
                  urls
                  [(json-response 200 {:id "j2" :status "IN_QUEUE"})
                   (json-response 200 {:status "FAILED" :error "worker crashed"})])]
      (-> (with-worker fetch
            #(complete {::dg/mode :generate ::dg/prompt "x"}))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (= "" text) "errors-as-values — empty text, never a rejection")
              (is (some? error) "a FAILED job resolves to :seon.ai/error")
              (is (not (contains? error :seon.ai/transport?))
                  "a failed job is a processing error — NOT retryable")
              (is (not (contains? error :seon.ai/status)))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest transient-503-then-success-recovers-under-with-retry
  ;; A RunPod 503 on submit (cold-start / capacity) is status-shaped and
  ;; retryable. with-retry! re-fires the whole complete (a fresh submit),
  ;; which then queues + polls to COMPLETED — proving the control adapter
  ;; inherits the turn loop's retry with zero new retry code.
  (async done
    (let [urls (atom [])
          fetch (scripted-fetch
                  urls
                  [(json-response 503 {:error "no workers available"})        ; submit #1 → 503
                   (json-response 200 {:id "j3" :status "IN_QUEUE"})           ; submit #2
                   (json-response 200 {:status "COMPLETED"
                                       :output {:text "recovered" :tok_per_s 480}})])]
      (-> (with-worker fetch
            (fn []
              (retry/with-retry!
                {:seon.retry/thunk    #(complete {::dg/mode :generate ::dg/prompt "x"})
                 :seon.retry/strategy (retry/max-retries (retry/constant-strategy 0) 3)
                 :seon.retry/retry?   retryable?})))
          (.then
            (fn [{result  :seon.retry/result
                  retries :seon.retry/retries}]
              (is (= 1 retries) "exactly one retry fired (the 503, then success)")
              (is (nil? (:seon.ai/error result)) "the recovered call has no error")
              (is (= "recovered" (:seon.ai/text result)) "the second submit polled to COMPLETED")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest fetch-throw-on-submit-is-transport-shaped
  (async done
    (-> (with-worker
          (fn [_ _] (js/Promise.reject (js/TypeError. "fetch failed")))
          #(complete {::dg/mode :generate ::dg/prompt "x"}))
        (.then
          (fn [{:seon.ai/keys [text error]}]
            (is (= "" text))
            (is (true? (:seon.ai/transport? error))
                "a thrown fetch (cold-start transient) maps to the retryable transport class")))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest abort-stops-poll-and-requests-one-remote-cancel
  (async done
    (let [calls      (atom [])
          controller (js/AbortController.)
          signal     (.-signal controller)
          fetch
          (fn [url init]
            (swap! calls conj {:url url :signal (.-signal init)})
            (cond
              (re-find #"/run$" url)
              (js/Promise.resolve
                (json-response 200 {:id "abort-job" :status "IN_QUEUE"}))

              (re-find #"/status/" url)
              (js/Promise.
                (fn [_ reject]
                  (.addEventListener
                    (.-signal init) "abort"
                    (fn [] (reject (js/DOMException. "aborted" "AbortError")))
                    #js{:once true})))

              (re-find #"/cancel/abort-job$" url)
              (js/Promise.resolve (json-response 200 {:id "abort-job"}))

              :else
              (js/Promise.reject (js/Error. (str "unexpected URL " url)))))]
      (-> (with-worker fetch
            (fn []
              (let [p (complete {::dg/mode :generate
                                 ::dg/prompt "x"
                                 :seon.ai/abort-signal signal})]
                (js/setTimeout #(.abort controller) 20)
                p)))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (= "" text))
              (is (true? (:seon.ai/timeout? error)))
              (is (not (contains? error :seon.ai/transport?))
                  "an owned abort is not a retryable network failure")
              (let [xs @calls]
                (is (= 3 (count xs)) "submit, one status, one cancel")
                (is (identical? signal (:signal (second xs)))
                    "poll fetch receives the exact attempt signal")
                (is (nil? (:signal (last xs)))
                    "remote cancel is independent of the already-aborted signal")
                (is (re-find #"/cancel/abort-job$" (:url (last xs)))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest missing-endpoint-is-a-legible-config-error
  (async done
    (let [called (atom 0)]
      (-> (with-env {"SEON_DG_ENDPOINT" nil "RUNPOD_API_KEY" "k"}
            (fn []
              (set! dg/*fetch* (fn [_ _] (swap! called inc) (js/Promise.resolve (json-response 200 {}))))
              (-> (js/Promise.resolve
                    (complete {::dg/mode :generate ::dg/prompt "x"}
                              (resolution)))
                  (.finally (fn [] (set! dg/*fetch* nil))))))
          (.then
            (fn [{:seon.ai/keys [text error]}]
              (is (= "" text) "config gap → envelope, not a throw")
              (is (zero? @called) "no fetch attempted when unconfigured")
              (is (re-find #"SEON_DG_ENDPOINT" (:seon.ai/msg error)))
              (is (not (contains? error :seon.ai/transport?)) "a config error is not retryable")))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))
