;;
;;       Copyright (c) 2017 Two Sigma Investments, LP.
;;       All Rights Reserved
;;
;;       THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF
;;       Two Sigma Investments, LP.
;;
;;       The copyright notice above does not evidence any
;;       actual or intended publication of such source code.
;;
(ns waiter.basic-test
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [qbits.jet.client.http :as http]
            [waiter.client-tools :refer :all]
            [waiter.service-description :as sd]
            [waiter.utils :as utils])
  (:import java.io.ByteArrayInputStream))

(deftest ^:parallel ^:integration-fast test-basic-functionality
  (testing-using-waiter-url
    (let [{:keys [service-id request-headers]}
          (make-request-with-debug-info
            {:x-waiter-name (rand-name)}
            #(make-kitchen-request waiter-url % :path "/hello"))]

      (testing "secrun"
        (log/info (str "Basic test using endpoint: /secrun"))
        (let [{:keys [body] :as response}
              (make-kitchen-request waiter-url request-headers :path "/secrun")]
          (assert-response-status response 200)
          (is (= "Hello World" body))))

      (testing "empty-body"
        (log/info "Basic test for empty body in request")
        (let [{:keys [body]} (make-kitchen-request
                               waiter-url
                               (assoc request-headers
                                 :accept "text/plain")
                               :path "/request-info")
              body-json (json/read-str (str body))]
          (is (get-in body-json ["headers" "authorization"]) (str body))
          (is (get-in body-json ["headers" "x-waiter-auth-principal"]) (str body))
          (is (get-in body-json ["headers" "x-cid"]) (str body))
          (is (nil? (get-in body-json ["headers" "content-type"])) (str body))
          (is (= "0" (get-in body-json ["headers" "content-length"])) (str body))
          (is (= "text/plain" (get-in body-json ["headers" "accept"])) (str body))))

      (testing "http methods"
        (log/info "Basic test for empty body in request")
        (let [http-method-helper (fn http-method-helper [http-method]
                                   (fn inner-http-method-helper [client url & [req]]
                                     (http/request client (merge req {:method http-method :url url}))))]
          (testing "http method: HEAD"
            (let [response (make-kitchen-request waiter-url request-headers
                                                 :http-method-fn (http-method-helper :head)
                                                 :path "/request-info")]
              (assert-response-status response 200)
              (is (str/blank? (:body response)))))
          (doseq [request-method [:delete :copy :get :move :patch :post :put]]
            (testing (str "http method: " (-> request-method name str/upper-case))
              (let [{:keys [body] :as response} (make-kitchen-request waiter-url request-headers
                                                                      :http-method-fn (http-method-helper request-method)
                                                                      :path "/request-info")
                    body-json (json/read-str (str body))]
                (assert-response-status response 200)
                (is (= (name request-method) (get body-json "request-method"))))))))

      (testing "content headers"
        (let [request-length 100000
              long-request (apply str (repeat request-length "a"))
              plain-resp (make-kitchen-request
                           waiter-url request-headers
                           :path "/request-info"
                           :body long-request)
              chunked-resp (make-kitchen-request
                             waiter-url
                             request-headers
                             :path "/request-info"
                             ; force a chunked request
                             :body (ByteArrayInputStream. (.getBytes long-request)))
              plain-body-json (json/read-str (str (:body plain-resp)))
              chunked-body-json (json/read-str (str (:body chunked-resp)))]
          (is (= (str request-length) (get-in plain-body-json ["headers" "content-length"])))
          (is (nil? (get-in plain-body-json ["headers" "transfer-encoding"])))
          (is (= "chunked" (get-in chunked-body-json ["headers" "transfer-encoding"])))
          (is (nil? (get-in chunked-body-json ["headers" "content-length"])))))

      (testing "large header"
        (let [all-chars (map char (range 33 127))
              random-string (fn [n] (reduce str (take n (repeatedly #(rand-nth all-chars)))))
              make-request (fn [header-size]
                             (log/info "making request with header size" header-size)
                             (make-kitchen-request waiter-url
                                                   (assoc request-headers :x-kitchen-long-string
                                                                          (random-string header-size))))]
          (let [response (make-request 2000)]
            (assert-response-status response 200))
          (let [response (make-request 4000)]
            (assert-response-status response 200))
          (let [response (make-request 8000)]
            (assert-response-status response 200))
          (let [response (make-request 16000)]
            (assert-response-status response 200))
          (let [response (make-request 20000)]
            (assert-response-status response 200))
          (let [response (make-request 24000)]
            (assert-response-status response 200))))

      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-basic-logs
  (testing-using-waiter-url
    (if (using-marathon? waiter-url)
      (let [waiter-headers {:x-waiter-name (rand-name)}
            {:keys [service-id]} (make-request-with-debug-info waiter-headers #(make-kitchen-request waiter-url %))]
        (let [active-instances (get-in (service-settings waiter-url service-id) [:instances :active-instances])
              log-url (:log-url (first active-instances))
              _ (log/debug "Log Url:" log-url)
              make-request-fn (fn [url] (make-request url "" :verbose true))
              {:keys [body] :as logs-response} (make-request-fn log-url)
              _ (assert-response-status logs-response 200)
              _ (log/debug "Response body:" body)
              log-files-list (walk/keywordize-keys (json/read-str body))
              stdout-file-link (:url (first (filter #(= (:name %) "stdout") log-files-list)))
              stderr-file-link (:url (first (filter #(= (:name %) "stderr") log-files-list)))]
          (is (every? #(str/includes? body %) ["stderr" "stdout" service-id])
              (str "Directory listing is missing entries: stderr, stdout, and " service-id
                   ": got response: " logs-response))
          (let [stdout-response (make-request-fn stdout-file-link)]
            (is (= 200 (:status stdout-response))
                (str "Expected 200 while getting stdout, got response: " stdout-response)))
          (let [stderr-response (make-request-fn stderr-file-link)]
            (is (= 200 (:status stderr-response))
                (str "Expected 200 while getting stderr, got response: " stderr-response))))
        (delete-service waiter-url service-id))
      (log/warn "test-basic-logs cannot run because the target Waiter is not using Marathon"))))

(deftest ^:parallel ^:integration-fast test-basic-backoff-config
  (let [path "/secrun"]
    (testing-using-waiter-url
      (log/info (str "Basic backoff config test using endpoint: " path))
      (let [{:keys [service-id] :as response}
            (make-request-with-debug-info
              {:x-waiter-name (rand-name)
               :x-waiter-restart-backoff-factor 2.5}
              #(make-kitchen-request waiter-url % :path path))
            service-settings (service-settings waiter-url service-id)]
        (assert-response-status response 200)
        (is (= 2.5 (get-in service-settings [:service-description :restart-backoff-factor])))
        (delete-service waiter-url service-id)))))

(deftest ^:parallel ^:integration-fast test-basic-shell-command
  (testing-using-waiter-url
    (let [headers {:x-waiter-name (rand-name)
                   :x-waiter-cmd (kitchen-cmd "-p $PORT0")}
          {:keys [status service-id]} (make-request-with-debug-info headers #(make-shell-request waiter-url %))
          _ (is (not (nil? service-id)))
          _ (is (= 200 status))
          service-settings (service-settings waiter-url service-id)
          command (get-in service-settings [:service-description :cmd])]
      (is (= (:x-waiter-cmd headers) command))
      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-basic-unsupported-command-type
  (testing-using-waiter-url
    (let [headers {:x-waiter-name (rand-name)
                   :x-waiter-version "1"
                   :x-waiter-cmd "false"
                   :x-waiter-cmd-type "fakecommand"}
          {:keys [body status]} (make-light-request waiter-url headers)]
      (is (= 400 status))
      (is (str/includes? body "Command type fakecommand is not supported")))))

(deftest ^:parallel ^:integration-fast test-header-metadata
  (testing-using-waiter-url
    (let [headers {:x-waiter-name (rand-name)
                   :x-waiter-metadata-foo "bar"
                   :x-waiter-metadata-baz "quux"
                   :x-waiter-metadata-beginDate "null"
                   :x-waiter-metadata-endDate "null"
                   :x-waiter-metadata-timestamp "20160713201333949"}
          {:keys [status service-id] :as response} (make-request-with-debug-info headers #(make-kitchen-request waiter-url %))
          value (:metadata (response->service-description waiter-url response))]
      (is (= 200 status))
      (is (= {:foo "bar", :baz "quux", :begindate "null", :enddate "null", :timestamp "20160713201333949"} value))
      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-last-request-time
  (testing-using-waiter-url
    (let [waiter-settings (waiter-settings waiter-url)
          metrics-sync-interval-ms (get-in waiter-settings [:metrics-config :metrics-sync-interval-ms])
          service-name (rand-name)
          headers {:x-kitchen-delay-ms (* 4 metrics-sync-interval-ms)
                   :x-waiter-name service-name
                   :x-waiter-cmd (kitchen-cmd "-p $PORT0")}
          {:keys [headers request-headers service-id] :as first-response}
          (make-request-with-debug-info headers #(make-kitchen-request waiter-url % :http-method-fn http/get))
          _ (assert-response-status first-response 200)
          canary-request-time-from-header (-> (get headers "x-waiter-request-date")
                                              (utils/str-to-date utils/formatter-rfc822))]
      (with-service-cleanup
        service-id
        (is (pos? metrics-sync-interval-ms))
        (let [service-last-request-time (service-id->last-request-time waiter-url service-id)]
          (is (pos? (.getMillis canary-request-time-from-header)))
          (is (pos? (.getMillis service-last-request-time)))
          (is (zero? (t/in-seconds (t/interval canary-request-time-from-header service-last-request-time)))))
        (make-kitchen-request waiter-url request-headers :http-method-fn http/get)
        (let [service-last-request-time (service-id->last-request-time waiter-url service-id)]
          (is (pos? (.getMillis service-last-request-time)))
          (is (t/before? canary-request-time-from-header service-last-request-time)))))))

(deftest ^:parallel ^:integration-fast test-list-apps
  (testing-using-waiter-url
    (let [service-id (:service-id (make-request-with-debug-info
                                    {:x-waiter-name (rand-name)}
                                    #(make-kitchen-request waiter-url %)))]
      (testing "without parameters"
        (let [service (service waiter-url service-id {})] ;; see my app as myself
          (is service)
          (is (-> (get service "last-request-time") utils/str-to-date .getMillis pos?))
          (is (pos? (get-in service ["service-description" "cpus"])) service)))

      (testing "waiter user disabled" ;; see my app as myself
        (let [service (service waiter-url service-id {"force" "false"})]
          (is service)
          (is (-> (get service "last-request-time") utils/str-to-date .getMillis pos?))
          (is (pos? (get-in service ["service-description" "cpus"])) service)))

      (testing "waiter user disabled and same user" ;; see my app as myself
        (let [service (service waiter-url service-id {"force" "false", "run-as-user" (retrieve-username)})]
          (is service)
          (is (-> (get service "last-request-time") utils/str-to-date .getMillis pos?))
          (is (pos? (get-in service ["service-description" "cpus"])) service)))

      (testing "different run-as-user" ;; no such app
        (let [service (service waiter-url service-id {"run-as-user" "test-user"}
                               :interval 2, :timeout 10)]
          (is (nil? service))))

      (testing "should not provide effective service description by default"
        (let [service (service waiter-url service-id {})]
          (is (nil? (get service "effective-parameters")))))

      (testing "should not provide effective service description when explicitly not requested"
        (let [service (service waiter-url service-id {"effective-parameters" "false"})]
          (is (nil? (get service "effective-parameters")))))

      (testing "should provide effective service description when requested"
        (let [service (service waiter-url service-id {"effective-parameters" "true"})]
          (is (= sd/service-description-keys (set (keys (get service "effective-parameters")))))))

      (delete-service waiter-url service-id))

    (let [current-user (retrieve-username)
          service-id (:service-id (make-request-with-debug-info
                                    {:x-waiter-name (rand-name)
                                     :x-waiter-run-as-user current-user}
                                    #(make-kitchen-request waiter-url %)))]
      (testing "list-apps-with-waiter-user-disabled-and-see-another-app" ;; can see another user's app
        (let [service (service waiter-url service-id {"force" "false", "run-as-user" current-user})]
          (is service)
          (is (-> (get service "last-request-time") utils/str-to-date .getMillis pos?))
          (is (pos? (get-in service ["service-description" "cpus"])) service)))
      (delete-service waiter-url service-id))))

; Marked explicit due to:
;   FAIL in (test-delete-app) (basic_test.clj:251)
; test-delete-app service-deleted-from-all-routers
; [CID=unknown] Expected status: 200, actual: 400
; Body:{"exception":["Read timed out",
; "java.net.SocketInputStream.socketRead0(Native Method)",
; "java.net.SocketInputStream.socketRead(SocketInputStream.java:116)"
; ...
; "marathonclj.rest.apps$delete_app.invoke(apps.clj:17)",
; "waiter.scheduler.marathon.MarathonScheduler$fn__18236.invoke(marathon.clj:260)",
; "waiter.scheduler$retry_on_transient_server_exceptions_fn$fn__17306.invoke(scheduler.clj:91)"
; ...
; "waiter.scheduler.marathon.MarathonScheduler.delete_app(marathon.clj:257)",
; "waiter.core$delete_app_handler.invokeStatic(core.clj:410)"
; ...
(deftest ^:parallel ^:integration-fast ^:explicit test-delete-app
  (testing-using-waiter-url
    "test-delete-app"
    (let [{:keys [service-id cookies]} (make-request-with-debug-info
                                         {:x-waiter-name (rand-name)}
                                         #(make-kitchen-request waiter-url %))]
      (testing "service-known-on-all-routers"
        (let [services (loop [routers (routers waiter-url)
                              services []]
                         (if-let [[_ router-url] (first routers)]
                           (recur (rest routers)
                                  (conj services
                                        (wait-for
                                          (fn []
                                            (let [{:keys [body]} (make-request router-url "/apps" :cookies cookies)
                                                  parsed-body (json/read-str body)]
                                              (first (filter #(= service-id (get % "service-id")) parsed-body))))
                                          :interval 2
                                          :timeout 30)))
                           services))]
          (is (every? #(and % (pos? (get-in % ["service-description" "cpus"]))) services)
              (str "Cannot find service: " service-id " in at least one router."))))

      (testing "service-deleted-from-all-routers"
        (let [{:keys [body] :as response} (make-request waiter-url (str "/apps/" service-id) :http-method-fn http/delete)]
          (assert-response-status response 200)
          (is body)
          (is (loop [routers (routers waiter-url)
                     result true]
                (if-let [[_ router-url] (first routers)]
                  (recur (rest routers)
                         (and result
                              (wait-for
                                (fn []
                                  (let [{:keys [body]} (make-request router-url "/apps" :cookies cookies)
                                        parsed-body (json/read-str body)]
                                    (empty? (filter #(= service-id (get % "service-id")) parsed-body))))
                                :interval 2
                                :timeout 30)))
                  result))
              (str "Service was not successfully deleted from the scheduler.")))))))

(deftest ^:parallel ^:integration-fast test-suspend-resume
  (testing-using-waiter-url
    (let [waiter-headers {:x-waiter-name (rand-name)}
          {:keys [service-id]} (make-request-with-debug-info waiter-headers #(make-kitchen-request waiter-url %))]
      (let [results (parallelize-requests 10 2
                                          #(let [response (make-kitchen-request waiter-url waiter-headers)]
                                             (= 200 (:status response)))
                                          :verbose true)]
        (is (every? true? results)))
      (log/info "Suspending service " service-id)
      (make-request waiter-url (str "/apps/" service-id "/suspend"))
      (let [results (parallelize-requests 10 2
                                          #(let [{:keys [body]} (make-kitchen-request waiter-url waiter-headers)]
                                             (str/includes? body "Service has been suspended"))
                                          :verbose true)]
        (is (every? true? results)))
      (log/info "Resuming service " service-id)
      (make-request waiter-url (str "/apps/" service-id "/resume"))
      (let [results (parallelize-requests 10 2
                                          #(let [_ (log/info "making kitchen request")
                                                 response (make-kitchen-request waiter-url waiter-headers)]
                                             (= 200 (:status response)))
                                          :verbose true)]
        (is (every? true? results)))
      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-override
  (testing-using-waiter-url
    (let [waiter-headers {:x-waiter-name (rand-name)}
          overrides {:max-instances 100
                     :min-instances 2
                     :scale-factor 0.3}
          {:keys [service-id]} (make-request-with-debug-info waiter-headers #(make-kitchen-request waiter-url %))
          override-endpoint (str "/apps/" service-id "/override")]
      (with-service-cleanup
        service-id
        (-> (make-request waiter-url override-endpoint :http-method-fn http/post :body (json/write-str overrides))
            (assert-response-status 200))
        (let [{:keys [body] :as response} (make-request waiter-url override-endpoint
                                                        :http-method-fn http/get
                                                        :body (json/write-str overrides))]
          (assert-response-status response 200)
          (let [response-data (-> body str json/read-str walk/keywordize-keys)]
            (is (= (retrieve-username) (:last-updated-by response-data)))
            (is (= overrides (:overrides response-data)))
            (is (= service-id (:service-id response-data)))
            (is (contains? response-data :time))))
        (let [service-settings (service-settings waiter-url service-id)
              service-description-overrides (-> service-settings :service-description-overrides :overrides)]
          (is (= overrides service-description-overrides)))
        (-> (make-request waiter-url override-endpoint :http-method-fn http/delete)
            (assert-response-status 200))
        (let [service-settings (service-settings waiter-url service-id)
              service-description-overrides (-> service-settings :service-description-overrides :overrides)]
          (is (not service-description-overrides)))))))

(deftest ^:parallel ^:integration-fast basic-waiter-auth-test
  (testing-using-waiter-url
    (log/info "Basic waiter-auth test")
    (let [{:keys [status body headers]} (make-request waiter-url "/waiter-auth")
          set-cookie (get headers "set-cookie")]
      (is (= 200 status))
      (is (str/includes? set-cookie "x-waiter-auth="))
      (is (str/includes? set-cookie "Max-Age="))
      (is (str/includes? set-cookie "Path=/"))
      (is (str/includes? set-cookie "HttpOnly=true"))
      (is (= (System/getProperty "user.name") (str body))))))

; Marked explicit due to:
;   FAIL in (test-killed-instances)
;   Not intersection between used and killed instances!
;   expected: (not-empty (set/intersection all-instance-ids killed-instance-ids))
;     actual: (not (not-empty #{}))
(deftest ^:parallel ^:integration-slow ^:explicit test-killed-instances
  (testing-using-waiter-url
    (let [headers {:x-waiter-name (rand-name)
                   :x-waiter-max-instances 6
                   :x-waiter-scale-up-factor 0.99
                   :x-waiter-scale-down-factor 0.85
                   :x-kitchen-delay-ms 5000}
          _ (log/info "making canary request...")
          {:keys [service-id]} (make-request-with-debug-info headers #(make-kitchen-request waiter-url %))
          request-fn (fn [] (->> #(make-kitchen-request waiter-url %)
                                 (make-request-with-debug-info headers)
                                 (:instance-id)))
          _ (log/info "starting parallel requests")
          all-instance-ids (->> request-fn
                                (parallelize-requests 12 5)
                                (set))
          _ (parallelize-requests 1 5 request-fn)]
      (log/info "waiting for at least one instance to get killed")
      (is (wait-for #(let [service-settings (service-settings waiter-url service-id)
                           killed-instance-ids (->> (get-in service-settings [:instances :killed-instances])
                                                    (map :id)
                                                    (set))]
                       (pos? (count killed-instance-ids)))
                    :interval 2 :timeout 30)
          (str "No killed instances found for " service-id))
      (let [service-settings (service-settings waiter-url service-id)
            killed-instance-ids (->> (get-in service-settings [:instances :killed-instances]) (map :id) (set))]
        (log/info "used instances (" (count all-instance-ids) "):" all-instance-ids)
        (log/info "killed instances (" (count killed-instance-ids) "):" killed-instance-ids)
        (is (not-empty (set/intersection all-instance-ids killed-instance-ids))
            "Not intersection between used and killed instances!"))
      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-basic-priority-support
  (testing-using-waiter-url
    (let [headers {:x-waiter-name (rand-name)
                   :x-waiter-distribution-scheme "simple" ;; disallow work-stealing interference from balanced
                   :x-waiter-max-instances 1}
          {:keys [cookies service-id]} (make-request-with-debug-info headers #(make-kitchen-request waiter-url %))
          router-url (some-router-url-with-assigned-slots waiter-url service-id)
          response-priorities-atom (atom [])
          num-threads 15
          request-priorities (vec (shuffle (range num-threads)))
          request-counter-atom (atom 0)
          make-prioritized-request (fn [priority delay-ms]
                                     (let [request-headers (assoc headers
                                                             :x-kitchen-delay-ms delay-ms
                                                             :x-waiter-priority priority)]
                                       (log/info "making kitchen request")
                                       (make-kitchen-request router-url request-headers :cookies cookies)))]
      (async/thread ; long request to make the following requests queue up
        (make-prioritized-request -1 5000))
      (Thread/sleep 500)
      (parallelize-requests num-threads 1
                            (fn []
                              (let [index (dec (swap! request-counter-atom inc))
                                    priority (nth request-priorities index)]
                                (make-prioritized-request priority 1000)
                                (swap! response-priorities-atom conj priority)))
                            :verbose true)
      ;; first item may be processed out of order as it can arrive before at the server
      (is (= (-> num-threads range reverse) @response-priorities-atom))
      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-multiple-ports
  (testing-using-waiter-url
    (let [num-ports 8
          waiter-headers {:x-waiter-name (rand-name)
                          :x-waiter-ports num-ports}
          {:keys [body service-id]}
          (make-request-with-debug-info waiter-headers #(make-kitchen-request waiter-url % :path "/environment"))
          body-json (json/read-str (str body))]
      (is (every? #(contains? body-json (str "PORT" %)) (range num-ports))
          (str body-json))
      (let [{:keys [extra-ports port] :as active-instance}
            (get-in (service-settings waiter-url service-id) [:instances :active-instances 0])]
        (log/info service-id "active-instance:" active-instance)
        (is (pos? port))
        (is (= (get body-json "PORT0") (str port)))
        (is (= (dec num-ports) (count extra-ports)) extra-ports)
        (is (every? pos? extra-ports) extra-ports)
        (is (->> (map #(= (get body-json (str "PORT" %1)) (str %2))
                      (range 1 (-> extra-ports count inc))
                      extra-ports)
                 (every? true?))))
      (delete-service waiter-url service-id))))

(deftest ^:parallel ^:integration-fast test-identical-version
  (testing-using-waiter-url
    (let [{:keys [cookies]} (make-request waiter-url "/waiter-auth")]
      (is (= 1 (->> (routers waiter-url)
                    vals
                    (map #(:git-version (waiter-settings % :cookies cookies)))
                    set
                    count))))))

(deftest ^:parallel ^:integration-fast test-cors-request-allowed
  (testing-using-waiter-url
    (let [{{:keys [kind]} :cors-config} (waiter-settings waiter-url)]
      (if (= kind "allow-all")
        (testing "cors allowed"
          ; Hit an endpoint that is guarded by CORS validation.
          ; There's nothing special about /state, any CORS validated endpoint will do.
          (let [{:keys [status] :as response} (make-request waiter-url "/state"
                                                            :headers {"origin" "example.com"})]
            (is (= 200 status) response)))
        (testing "cors not allowed"
          (let [{:keys [status] :as response} (make-request waiter-url "/state"
                                                            :http-method-fn http/get
                                                            :headers {"origin" "badorigin.com"})]
            (is (= 403 status) response))
          (let [{:keys [status] :as response} (make-request waiter-url "/state"
                                                            :http-method-fn http/post
                                                            :headers {"origin" "badorigin.com"})]
            (is (= 403 status) response))
          (let [options (fn ([client url request-map]
                             (http/request client
                                           (into {:method :options :url url}
                                                 request-map))))
                {:keys [status] :as response} (make-request waiter-url "/state"
                                                            :http-method-fn options
                                                            :headers {"origin" "badorigin.com"})]
            (is (= 403 status) response)))))))

(deftest ^:parallel ^:integration-fast test-error-handling
  (testing-using-waiter-url
    (testing "text/plain default"
      (let [{:keys [body headers status]} (make-request waiter-url "/404")]
        (is (= 404 status))
        (is (= "text/plain" (get headers "content-type")))
        (is (str/includes? body "Waiter Error 404"))
        (is (str/includes? body "================"))))
    (testing "text/plain explicit"
      (let [{:keys [body headers status]} (make-request waiter-url "/404" :headers {"accept" "text/plain"})]
        (is (= 404 status))
        (is (= "text/plain" (get headers "content-type")))
        (is (str/includes? body "Waiter Error 404"))
        (is (str/includes? body "================"))))
    (testing "text/html"
      (let [{:keys [body headers status]} (make-request waiter-url "/404" :headers {"accept" "text/html"})]
        (is (= 404 status))
        (is (= "text/html" (get headers "content-type")))
        (is (str/includes? body "Waiter Error 404"))
        (is (str/includes? body "<html>"))))
    (testing "application/json explicit"
      (let [{:keys [body headers status]} (make-request waiter-url "/404" :headers {"accept" "application/json"})
            {:strs [waiter-error]} (try (json/read-str body)
                                        (catch Throwable _
                                          (is false (str "Could not parse body that is supposed to be JSON:\n" body))))]
        (is (= 404 status))
        (is (= "application/json" (get headers "content-type")))
        (is waiter-error (str "Could not find waiter-error element in body " body))
        (let [{:strs [status]} waiter-error]
          (is (= 404 status)))))
    (testing "application/json implied by content-type"
      (let [{:keys [body headers status]} (make-request waiter-url "/404" :headers {"content-type" "application/json"})
            {:strs [waiter-error]} (try (json/read-str body)
                                        (catch Throwable _
                                          (is false (str "Could not parse body that is supposed to be JSON:\n" body))))]
        (is (= 404 status))
        (is (= "application/json" (get headers "content-type")))
        (is waiter-error (str "Could not find waiter-error element in body " body))
        (let [{:strs [status]} waiter-error]
          (is (= 404 status)))))
    (testing "support information included"
      (let [{:keys [body headers status]} (make-request waiter-url "/404" :headers {"accept" "application/json"})
            {:keys [messages support-info]} (waiter-settings waiter-url)
            {:strs [waiter-error]} (try (json/read-str body)
                                        (catch Throwable _
                                          (is false (str "Could not parse body that is supposed to be JSON:\n" body))))]

        (is (= 404 status))
        (is (= "application/json" (get headers "content-type")))
        (is (= (:not-found messages) (get waiter-error "message")))
        (is waiter-error (str "Could not find waiter-error element in body " body))
        (let [{:strs [status]} waiter-error]
          (is (= 404 status))
          (is (= support-info (-> (get waiter-error "support-info")
                                  (walk/keywordize-keys)))))))))

(deftest ^:parallel ^:integration-fast test-welcome-page
  (testing-using-waiter-url
    (testing "default text/plain"
      (let [{:keys [body headers status]} (make-request waiter-url "/")]
        (is (= 200 status))
        (is (= "text/plain" (get headers "content-type")))
        (is (str/includes? body "Welcome to Waiter"))))
    (testing "accept text/plain"
      (let [{:keys [body headers status]} (make-request waiter-url "/" :headers {"accept" "text/plain"})]
        (is (= 200 status))
        (is (= "text/plain" (get headers "content-type")))
        (is (str/includes? body "Welcome to Waiter"))))
    (testing "accept text/html"
      (let [{:keys [body headers status]} (make-request waiter-url "/" :headers {"accept" "text/html"})]
        (is (= 200 status))
        (is (= "text/html" (get headers "content-type")))
        (is (str/includes? body "Welcome to Waiter"))))
    (testing "accept application/json"
      (let [{:keys [body headers status]} (make-request waiter-url "/" :headers {"accept" "application/json"})
            json-data (try (json/read-str body)
                           (catch Exception _
                             (is false ("Not json:\n" body))))]
        (is (= 200 status))
        (is (= "application/json" (get headers "content-type")))
        (is (= "Welcome to Waiter" (get json-data "message")))))
    (testing "only GET"
      (let [{:keys [body status]} (make-request waiter-url "/" :http-method-fn http/post)]
        (is (= 405 status))
        (is (str/includes? body "Only GET supported"))))))
