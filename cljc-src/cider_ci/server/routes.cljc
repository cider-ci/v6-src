(ns cider-ci.server.routes
  (:require
    #?(:cljs [cider-ci.server.html.history-navigation :as navigation :refer []])
    [reitit.core :as reitit]
    [taoensso.timbre :refer [debug info warn error]]
    [cider-ci.utils.query-params :as query-params]
    [cuerdas.core :as string :refer []]
    ))

(def routes
  [["/" {:name :root
         :auth-http-unsave #{}
         :auth-http-save #{:public}}]
   ["/init" {:name :init
             :no-sign-in-page true
             :auth-http-save #{:public}
             :auth-http-unsave #{:public}}]
   ["/sign-in" {:auth-http-unsave #{:public}}
    ["" {:name :sign-in}]
    ["/authenticate/password" {:name :sign-in-authenticate-password}]]
   ["/sign-out" {:name :sign-out
                 :auth-http-unsave #{:public}
                 :auth-http-save #{:public}}]
   ["/users"
    ["/" {:name :users
          :auth-http-save #{:admin}
          :auth-http-unsave #{:admin}}
     [":user-id"
      ["" {:name :user
           :auth-http-save #{:admin :self}
           :auth-http-unsave #{:admin}}]
      ["/password" {:name :user-password
                    :auth-http-unsave #{:self :admin}}]
      ["/email-addresses/" {:name :user-email-addresses
                            :auth-http-save #{:self}
                            :auth-http-unsave #{:admin}}]]]]])

(comment (path :user {:user-id "123"})
         (path :user-password {:user-id "123"})
         )


(def router (reitit/router routes))

(def routes-flattened (reitit/routes router))

;(reitit/match-by-name router :admin-stores)
;(reitit/match->path (reitit/match-by-name router :upload {:upload-id 5}))
;(reitit/match-by-path router "/media-service/")

(defn route [path]
  (-> path
      (string/split #"\?" )
      first
      (->> (reitit/match-by-path router))))

(defn path
  ([kw]
   (path kw {}))
  ([kw route-params]
   (path kw route-params {}))
  ([kw route-params query-params]
   (when-let [p (reitit/match->path
                  (reitit/match-by-name
                    router kw route-params))]
     (if (seq query-params)
       (str p "?" (query-params/encode query-params))
       p))))

#?(:cljs
   (defn navigate!
     ([url]
      (navigation/navigate! url nil))
     ([url event &{:keys [reload]
                   :or {reload false}}]
      (if reload
        (set! js/window.location url)
        (navigation/navigate! url event)))))
