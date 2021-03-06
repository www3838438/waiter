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
(ns waiter.auth.kerberos
  (:require [clj-time.core :as t]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [waiter.auth.authentication :as auth]
            [waiter.auth.spnego :as spnego]
            [waiter.utils :as utils]))

(defn get-opt-in-accounts
  "Returns the list of users whose tickets are prestashed on host"
  [host]
  (try
    (let [{:keys [exit out err]} (shell/sh "krb5_prestash" "query" "host" host)]
      (if (zero? exit)
        (set (map #(first (str/split % #"@" 2)) (str/split-lines out)))
        (do
          (log/error "Failed to reload prestash cache: " err)
          nil)))
    (catch Exception e
      (log/error e "Failed to update prestash cache")
      nil)))

; use nil to initialize cache so that if it fails to populate, we can return true for all users
(let [prestash-cache (atom nil)]
  (defn refresh-prestash-cache
    "Update the cache of users with prestashed kerberos tickets"
    [host]
    (when-let [users (get-opt-in-accounts host)]
      (reset! prestash-cache users)
      (log/debug "refreshed the prestash cache with" (count users) "users")
      users))

  (defn start-prestash-cache-maintainer
    "Starts an async/go-loop to maintain the prestash-cache."
    [max-update-interval min-update-interval host query-chan]
    (let [exit-chan (async/chan 1)]
      (refresh-prestash-cache host)
      (async/go-loop [{:keys [timeout-chan last-updated] :as current-state}
                      {:timeout-chan (async/timeout max-update-interval)
                       :last-updated (t/now)
                       :continue-looping true}]
        (let [[args chan] (async/alts! [exit-chan timeout-chan query-chan] :priority true)
              new-state
              (condp = chan
                exit-chan (assoc current-state :continue-looping false)
                timeout-chan
                (do
                  (refresh-prestash-cache host)
                  (assoc current-state :timeout-chan (async/timeout max-update-interval)
                                       :last-updated (t/now)))
                query-chan
                (let [{:keys [response-chan]} args]
                  (if (t/before? (t/now) (t/plus last-updated (t/millis min-update-interval)))
                    (do
                      (async/>! response-chan @prestash-cache)
                      current-state)
                    (let [users (refresh-prestash-cache host)]
                      (async/>! response-chan users)
                      (assoc current-state :timeout-chan (async/timeout max-update-interval)
                             :last-updated (t/now))))))]
          (when (:continue-looping new-state)
            (recur new-state))))
      {:exit-chan exit-chan
       :query-chan query-chan}))

  (defn is-prestashed?
    "Returns true if the user has prestashed
    tickets and false otherwise. If the cache has
    not been populated, returns true for all users."
    [user]
    (let [users @prestash-cache]
      (or (empty? users) (contains? users user)))))

(defn check-has-prestashed-tickets
  "Checks if the run-as-user has prestashed tickets available. Throws an exception if not."
  [query-chan run-as-user service-id]
  (when (not (is-prestashed? run-as-user))
    (let [response-chan (async/promise-chan)
          _ (async/>!! query-chan {:response-chan response-chan})
          [users chan] (async/alts!! [response-chan (async/timeout 1000)] :priority true)]
      (when (and (= response-chan chan) (not (contains? users run-as-user)))
        (throw (ex-info "No prestashed tickets available"
                        {:message (utils/message :prestashed-tickets-not-available)
                         :service-id service-id
                         :status 403
                         :user run-as-user}))))))

(defrecord KerberosAuthenticator [password query-chan]

  auth/Authenticator

  (auth-type [_]
    :kerberos)

  (check-user [_ user service-id]
    (check-has-prestashed-tickets query-chan user service-id))

  (wrap-auth-handler [_ request-handler]
    (spnego/require-gss request-handler password)))

(defn kerberos-authenticator
  "Factory function for creating KerberosAuthenticator"
  [{:keys [password prestash-cache-min-refresh-ms prestash-cache-refresh-ms prestash-query-host]}]
  {:pre [(not-empty password)
         (utils/pos-int? prestash-cache-min-refresh-ms)
         (utils/pos-int? prestash-cache-refresh-ms)
         (not (str/blank? prestash-query-host))]}
  (let [query-chan (async/chan 1024)]
    (start-prestash-cache-maintainer prestash-cache-refresh-ms prestash-cache-min-refresh-ms prestash-query-host query-chan)
    (->KerberosAuthenticator password query-chan)))
