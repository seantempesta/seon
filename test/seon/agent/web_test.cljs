(ns seon.agent.web-test
  "Envelope-contract tests for `seon.agent.web/fetch`.

   The contract under test — every outcome RESOLVES to a
   :seon.agent.web/fetch-response (errors are values, never a throw):

   1. HTML → markdown extraction: full text lands in a blob (my.blob),
      the response carries the projection + a TOKEN-capped preview with
      HONEST totals (total-tokens > preview-tokens), links, and the
      blob-hash pages the WHOLE doc via my.blob/text.
   2. SSRF: a loopback/private-range URL is refused BEFORE any transport;
      a redirect that LANDS on a private range is refused on that hop.
   3. A non-2xx status is ok? true with the real status — the fetch RAN
      (the shell ok?-means-RAN precedent); only a genuine transport
      failure (thrown/rejected fetch) is an error value.
   4. Binary content is a legible refusal naming the content-type.
   5. The private transport ceiling bounds streamed body reads and marks the
      result truncated; it is not a caller-controlled public request dial.

   Hermetic: the transport (int/!fetch-impl), DNS resolver
   (int/!lookup-impl), and web-access POLICY (int/!policy-override) are
   FAKED — no network, no config file staged. Blobs go to a pid-scoped
   tmp dir; each test runs against a fresh :memory conn root-set! as
   db/*conn* (the my.blob-test pattern). SEON_WEB is granted for the run
   and restored after; the policy baseline is :public-only (each :after
   restores it), individual tests override to :open / :allowlist to prove
   those modes."
  (:require
    ["node:fs" :as nfs]
    ["node:path" :as npath]
    [cljs.test :refer [deftest is async use-fixtures]]
    [clojure.string :as str]
    [datahike.api :as d]
    [my.blob :as blob]
    [seon.agent.web :as web]
    [seon.agent.web.internal :as int]
    [seon.ai.tokens :as tokens]
    [seon.client :as client]
    [seon.db :as db]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Fixtures — pid-scoped blob dir, SEON_WEB grant, faked transport/DNS.
;; ---------------------------------------------------------------------------

(def ^:private fixture-dir
  (.resolve npath (str "tmp/web-test-" (.-pid js/process))))

(defonce ^:private !saved-dir (atom nil))
(defonce ^:private !saved-env (atom nil))

;; The :public-only baseline every test starts from — the SSRF-safe policy.
(def ^:private public-only {:seon.agent.web/policy :public-only})

(use-fixtures :once
  {:before (fn []
             (reset! !saved-dir @blob/!dir)
             (reset! blob/!dir fixture-dir)
             (.rmSync nfs fixture-dir #js {:recursive true :force true})
             (reset! !saved-env (aget (.-env js/process) "SEON_WEB"))
             (aset (.-env js/process) "SEON_WEB" "1")
             (reset! int/!policy-override public-only))
   :after  (fn []
             (reset! blob/!dir @!saved-dir)
             (.rmSync nfs fixture-dir #js {:recursive true :force true})
             (reset! int/!policy-override nil)
             (if-some [v @!saved-env]
               (aset (.-env js/process) "SEON_WEB" v)
               (js-delete (.-env js/process) "SEON_WEB")))})

(use-fixtures :each
  {:after (fn []
            (reset! int/!fetch-impl nil)
            (reset! int/!lookup-impl nil)
            (reset! int/!policy-override public-only))})

(defn- fresh-conn []
  (let [cfg {:store              {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history?      true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then (fn [conn]
                 (-> (db/ensure-provenance! {:seon.db/conn conn})
                     (.then (fn [_]
                              (d/transact!
                                conn
                                {:tx-data (into (db/malli->datahike-schema
                                                  client/agent-bootstrap-attrs)
                                                (db/tx-meta-datahike-schema))})))
                     (.then (fn [_] conn))))))))

(defn- with-conn [body]
  (-> (fresh-conn)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (js/Promise.resolve (body conn))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(defn- run-test [chain done]
  (-> (with-conn (fn [conn] (chain conn)))
      (.then (fn [_] (done)))
      (.catch (fn [e] (is false (str "threw — " e)) (done)))))

;; A public-IP DNS answer so a hostname passes the SSRF guard in tests.
(defn- public-dns [] (fn [_host] (js/Promise.resolve #js [#js {:address "93.184.216.34"}])))

;; A faked transport: a (url -> js/Response) dispatcher.
(defn- fake-fetch [url->resp]
  (fn [url _init] (js/Promise.resolve (url->resp url))))

(defn- html-response [html]
  (js/Response. html #js {:status 200 :headers #js {"content-type" "text/html; charset=utf-8"}}))

;; ---------------------------------------------------------------------------
;; 1. HTML → markdown + blob + capped preview with honest totals.
;; ---------------------------------------------------------------------------

(def ^:private sample-html
  (str "<!doctype html><html><head><title>Hello Title</title></head><body>"
       "<article><h1>Main Heading</h1>"
       (apply str (repeat 40 "<p>This is a paragraph of real body content that readability keeps. </p>"))
       "<p>See <a href=\"/next\">the next page</a> for more.</p>"
       "</article></body></html>"))

(deftest html-extracts-blobs-and-previews-honestly
  (async done
    (reset! int/!lookup-impl (public-dns))
    (reset! int/!fetch-impl (fake-fetch (fn [_] (html-response sample-html))))
    (run-test
      (fn [conn]
        (-> (web/fetch {:seon.agent.web/url                "https://example.com/page"
                        :seon.agent.web/max-preview-tokens 5})
            (.then (fn [{ok?     :seon.agent.web/ok?
                         status  :seon.agent.web/status
                         title   :seon.agent.web/title
                         extr    :seon.agent.web/extractor
                         preview :seon.agent.web/preview
                         ptok    :seon.agent.web/preview-tokens
                         total   :seon.agent.web/total-tokens
                         hash    :seon.agent.web/blob-hash
                         links   :seon.agent.web/links
                         trunc?  :seon.agent.web/truncated?}]
                     (set! db/*conn* conn)
                     (is (true? ok?))
                     (is (= 200 status))
                     (is (= "Hello Title" title) "the <title> rode through extraction")
                     (is (contains? #{:readability :raw} extr) "honest extractor provenance")
                     (is (some? (re-matches #"[0-9a-f]{64}" hash)) "full text lands in a content-addressed blob")
                     (is (false? trunc?) "body stayed within the private transport ceiling")
                     ;; honest totals — the preview is a SMALL slice, never the whole
                     (is (> total ptok) "total-tokens exceeds the capped preview")
                     (is (<= ptok 6) "preview honors max-preview-tokens (+ellipsis)")
                     (is (str/ends-with? preview "…") "the preview cut is marked")
                     (is (seq links) "extracted links are carried")
                     (is (some #(str/includes? (:seon.agent.web/href %) "/next") links)
                         "the in-page link was absolutized + kept")
                     ;; the blob pages the FULL document, honestly
                     (let [g (blob/get {:my.blob/hash hash})]
                       (is (= total (tokens/estimate (:my.blob/content g)))
                           "blob content == the full extracted markdown")
                       (is (str/includes? (:my.blob/content g) "Main Heading")))
                     (let [t (blob/text {:my.blob/hash hash})]
                       (is (true? (:my.blob/ok? t)) "my.blob/text pages the stored doc")))))
        )
      done)))

;; ---------------------------------------------------------------------------
;; 2a. Private-range URL — refused before any transport.
;; ---------------------------------------------------------------------------

(deftest loopback-url-is-blocked
  (async done
    ;; a fetch impl that would THROW if called — proves we refuse pre-transport
    (reset! int/!fetch-impl (fn [_ _] (throw (js/Error. "transport must not run for a blocked host"))))
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "http://127.0.0.1:7891/store"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (re-find #"(?i)private|blocked" msg) "names the SSRF refusal")
                     (is (str/includes? msg "127.0.0.1") "names the offending address")))))
      done)))

;; ---------------------------------------------------------------------------
;; 2a'. The :open policy reaches loopback — bench clusters serving loopback
;; fixtures. Policy is host-owned config; grants surfaces the resolved mode.
;; ---------------------------------------------------------------------------

(deftest open-policy-permits-loopback
  (async done
    (reset! int/!fetch-impl
            (fake-fetch (fn [_] (html-response "<html><body><p>Established in 1920.</p></body></html>"))))
    (reset! int/!policy-override {:seon.agent.web/policy :open})
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "http://127.0.0.1:64999/history.html"})
            (.then (fn [{ok?     :seon.agent.web/ok?
                         preview :seon.agent.web/preview}]
                     (is (true? ok?) "loopback fetch RUNS under the :open policy")
                     (is (str/includes? (str preview) "1920") "the fixture body came through")
                     (is (= :open (:seon.agent.web/policy (web/grants)))
                         "grants surfaces the resolved policy")))))
      done)))

;; ---------------------------------------------------------------------------
;; 2a''. The :allowlist policy gates by domain — an in-list host (or its
;; subdomain) is reachable, an out-of-list host is refused.
;; ---------------------------------------------------------------------------

(deftest allowlist-policy-gates-by-domain
  (async done
    (reset! int/!lookup-impl (public-dns))
    (reset! int/!fetch-impl (fake-fetch (fn [_] (html-response "<html><body><p>allowed body</p></body></html>"))))
    (reset! int/!policy-override
            {:seon.agent.web/policy :allowlist
             :seon.agent.web/allowed-domains ["example.com"]})
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "https://docs.example.com/page"})
            (.then (fn [{ok? :seon.agent.web/ok?}]
                     (is (true? ok?) "a subdomain of a listed domain is reachable")
                     (is (= ["example.com"] (:seon.agent.web/allowed-domains (web/grants)))
                         "grants surfaces the allowlist")))
            (.then (fn [_]
                     (web/fetch {:seon.agent.web/url "https://evil.org/page"})))
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?) "an out-of-list host is refused")
                     (is (re-find #"(?i)allowlist" msg) "the refusal names the allowlist")))))
      done)))

;; ---------------------------------------------------------------------------
;; 2b. Redirect that LANDS on a private range — refused on that hop.
;; ---------------------------------------------------------------------------

(deftest redirect-to-private-range-is-blocked
  (async done
    (reset! int/!lookup-impl (public-dns)) ; example.com resolves public
    (reset! int/!fetch-impl
            (fake-fetch (fn [url]
                          (if (str/includes? url "example.com")
                            (js/Response. nil #js {:status 302
                                                   :headers #js {"location" "http://127.0.0.1/admin"}})
                            (throw (js/Error. "must not fetch the private redirect target"))))))
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "https://example.com/start"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (re-find #"(?i)private|blocked" msg) "the redirect hop was SSRF-checked")
                     (is (str/includes? msg "127.0.0.1"))))))
      done)))

;; ---------------------------------------------------------------------------
;; 3a. Non-2xx — ok? true with the real status: the fetch RAN and the error
;; page's body is a valid result (the shell ok?-means-RAN precedent).
;; ---------------------------------------------------------------------------

(deftest non-2xx-is-ok-with-status
  (async done
    (reset! int/!lookup-impl (public-dns))
    (reset! int/!fetch-impl
            (fake-fetch (fn [_] (js/Response. "<html><body><p>not found here</p></body></html>"
                                              #js {:status 404
                                                   :headers #js {"content-type" "text/html"}}))))
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "https://example.com/missing"})
            (.then (fn [{ok?     :seon.agent.web/ok?
                         status  :seon.agent.web/status
                         preview :seon.agent.web/preview}]
                     (is (true? ok?) "the fetch RAN — a 404 is a result, not an error")
                     (is (= 404 status) "the real status rides the success envelope")
                     (is (string? preview) "the error page's body is extracted + previewed")
                     (is (str/includes? preview "not found") "the 404 body content came through")))))
      done)))

;; ---------------------------------------------------------------------------
;; 3b. A genuine transport failure (rejected fetch) — still an error value.
;; ---------------------------------------------------------------------------

(deftest transport-failure-is-an-error-value
  (async done
    (reset! int/!lookup-impl (public-dns))
    (reset! int/!fetch-impl
            (fn [_ _] (js/Promise.reject (js/Error. "ECONNREFUSED 93.184.216.34:443"))))
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "https://example.com/down"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?) "could not fetch at all — the error envelope")
                     (is (str/includes? msg "ECONNREFUSED") "the transport error is named")))))
      done)))

;; ---------------------------------------------------------------------------
;; 4. Binary content — legible refusal naming the content-type.
;; ---------------------------------------------------------------------------

(deftest binary-content-is-refused-legibly
  (async done
    (reset! int/!lookup-impl (public-dns))
    (reset! int/!fetch-impl
            (fake-fetch (fn [_] (js/Response. " PNGDATA" #js {:status 200
                                                                  :headers #js {"content-type" "image/png"}}))))
    (run-test
      (fn [_]
        (-> (web/fetch {:seon.agent.web/url "https://example.com/logo.png"})
            (.then (fn [{ok? :seon.agent.web/ok?
                         msg :seon.error/message}]
                     (is (false? ok?))
                     (is (re-find #"(?i)binary" msg))
                     (is (str/includes? msg "image/png") "names the refused content-type")))))
      done)))

;; ---------------------------------------------------------------------------
;; 5. The transport's RAM guard stays private; public callers control only the
;; token-capped decoded preview.
;; ---------------------------------------------------------------------------

(deftest fetch-request-exposes-only-the-token-content-size-dial
  (let [request-keys (->> (rest (schema/schema-definition
                                  :seon.agent.web/fetch-request))
                          (keep #(when (vector? %) (first %)))
                          set)]
    (is (= #{:seon.agent.web/url
             :seon.agent.web/timeout-ms
             :seon.agent.web/max-preview-tokens
             :seon.agent.web/max-age-ms}
           request-keys)
        "the fetch request has one public content-size dial, denominated in tokens")))

(deftest private-body-reader-enforces-its-transport-ceiling
  (async done
    (let [private-limit 64
          response      (js/Response. (apply str (repeat 5000 "A")))]
      (-> (int/read-body-capped response private-limit)
          (.then (fn [^js body]
                   (is (true? (.-truncated body)))
                   (is (= private-limit (count (.-text body)))
                       "the internal reader retains only the bounded prefix")
                   (done)))
          (.catch (fn [e]
                    (is false (str "private body reader rejected — " e))
                    (done)))))))
