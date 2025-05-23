(ns metabase.api.util
  "Random utilty endpoints for things that don't belong anywhere else in particular, e.g. endpoints for certain admin
  page tasks."
  (:require
   [clj-http.client :as http]
   [crypto.random :as crypto-random]
   [environ.core :refer [env]]
   [metabase.analytics.core :as analytics]
   [metabase.api.common :as api]
   [metabase.api.common.validation :as validation]
   [metabase.api.macros :as api.macros]
   [metabase.api.open-api :as open-api]
   [metabase.config :as config]
   [metabase.db :as mdb]
   [metabase.driver :as driver]
   [metabase.eid-translation.core :as eid-translation]
   [metabase.logger :as logger]
   [metabase.premium-features.core :as premium-features]
   [metabase.util.json :as json]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.util.system-info :as u.system-info]
   [ring.util.response :as response]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(api.macros/defendpoint :post "/password_check"
  "Endpoint that checks if the supplied password meets the currently configured password complexity rules."
  [_route-params
   _query-params
   _body :- [:map
             [:password ms/ValidPassword]]]
  ;; if we pass the su/ValidPassword test we're g2g
  {:valid true})

(api.macros/defendpoint :get "/logs"
  "Logs."
  []
  (validation/check-has-application-permission :monitoring)
  (logger/messages))

(api.macros/defendpoint :get "/stats"
  "Anonymous usage stats. Endpoint for testing, and eventually exposing this to instance admins to let them see
  what is being phoned home."
  []
  (validation/check-has-application-permission :monitoring)
  (analytics/legacy-anonymous-usage-stats))

(api.macros/defendpoint :get "/random_token"
  "Return a cryptographically secure random 32-byte token, encoded as a hexadecimal string.
   Intended for use when creating a value for `embedding-secret-key`."
  []
  {:token (crypto-random/hex 32)})

(defn- product-feedback-url
  "Product feedback url. When not prod, reads `MB_PRODUCT_FEEDBACK_URL` from the environment to prevent development
  feedback from hitting the endpoint."
  []
  (if config/is-prod?
    "https://prod-feedback.metabase.com/api/v1/crm/product-feedback"
    (env :mb-product-feedback-url)))

(mu/defn send-feedback!
  "Sends the feedback to the api endpoint"
  [comments :- [:maybe ms/NonBlankString]
   source :- ms/NonBlankString
   email :- [:maybe ms/NonBlankString]]
  (try (http/post (product-feedback-url)
                  {:content-type :json
                   :body         (json/encode {:comments comments
                                               :source   source
                                               :email    email})})
       (catch Exception e
         (log/warn e)
         (throw e))))

(api.macros/defendpoint :post "/product-feedback"
  "Endpoint to provide feedback from the product"
  [_route-params
   _query-params
   {:keys [comments source email]} :- [:map
                                       [:comments {:optional true} [:maybe ms/NonBlankString]]
                                       [:source   ms/NonBlankString]
                                       [:email    {:optional true} [:maybe ms/NonBlankString]]]]
  (future (send-feedback! comments source email))
  api/generic-204-no-content)

(defn- metabase-info
  "Make it easy for the user to tell us what they're using"
  []
  (merge
   {:databases            (t2/select-fn-set :engine :model/Database)
    :run-mode             (config/config-kw :mb-run-mode)
    :plan-alias           (or (premium-features/plan-alias) "")
    :version              config/mb-version-info
    :settings             {:report-timezone (driver/report-timezone)}
    :hosting-env          (analytics/environment-type)
    :application-database (mdb/db-type)}
   (when-not (premium-features/is-hosted?)
     {:application-database-details (t2/with-connection [^java.sql.Connection conn]
                                      (let [metadata (.getMetaData conn)]
                                        {:database    {:name    (.getDatabaseProductName metadata)
                                                       :version (.getDatabaseProductVersion metadata)}
                                         :jdbc-driver {:name    (.getDriverName metadata)
                                                       :version (.getDriverVersion metadata)}}))})
   (when (premium-features/airgap-enabled)
     {:airgap-token       :enabled
      :max-users          (premium-features/max-users-allowed)
      :current-user-count (premium-features/active-users-count)
      :valid-thru         (:valid-thru (premium-features/token-status))})))

(api.macros/defendpoint :get "/bug_report_details"
  "Returns version and system information relevant to filing a bug report against Metabase."
  []
  (validation/check-has-application-permission :monitoring)
  (cond-> {:metabase-info (metabase-info)}
    (not (premium-features/is-hosted?))
    (assoc :system-info (u.system-info/system-info))))

(api.macros/defendpoint :get "/diagnostic_info/connection_pool_info"
  "Returns database connection pool info for the current Metabase instance."
  []
  (validation/check-has-application-permission :monitoring)
  (let [pool-info (analytics/connection-pool-info)
        headers   {"Content-Disposition" "attachment; filename=\"connection_pool_info.json\""}]
    (assoc (response/response {:connection-pools pool-info}) :headers headers, :status 200)))

(api.macros/defendpoint :post "/entity_id"
  "Translate entity IDs to model IDs."
  [_route-params
   _query-params
   {:keys [entity_ids]} :- [:map
                            [:entity_ids :map]]]
  {:entity_ids (eid-translation/model->entity-ids->ids entity_ids)})

(api.macros/defendpoint :get "/openapi"
  "Return the OpenAPI specification for the Metabase API."
  []
  (api/check-superuser)
  {:status 200
   :body (merge
          (open-api/root-open-api-object @(requiring-resolve 'metabase.api-routes.core/routes))
          {:servers [{:url "" :description "Metabase API"}]})})
