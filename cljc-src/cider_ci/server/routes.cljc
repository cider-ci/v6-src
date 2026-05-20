(ns cider-ci.server.routes
  (:require
   #?(:cljs [cider-ci.server.html.history-navigation :as navigation :refer []])
   [reitit.core :as reitit]
   [taoensso.timbre :refer [debug info warn error]]
   [cider-ci.utils.query-params :as query-params]
   [cuerdas.core :as string :refer []]
   [taoensso.timbre :refer [debug info warn error spy]]))

(def projects
  ["/projects"
   ["/" {:name :projects
         :auth-http-safe #{:user}
         :auth-http-unsafe #{:admin}}]
   ["/:project-id"
    ["" {:name :project
         :auth-http-safe #{:user}
         :auth-http-unsafe #{:admin}}]
    ["/fetch" {:name :project-fetch
               :auth-http-unsafe #{:admin}}]
    ["/commits/:commit-id" {:name :project-commit
                            :auth-http-safe #{:user}}]
    ["/branches/*branch-name" {:name :project-branch
                               :auth-http-safe #{:user}}]]])

(def users
  ["/users"
   ["/" {:name :users
         :auth-http-safe #{:admin}
         :auth-http-unsafe #{:admin}}]
   ["/:user-id"
    ["" {:name :user
         :auth-http-safe #{:self}}]
    ["/password" {:name :user-password
                  :auth-http-unsafe #{:self :admin}}]
    ["/email-addresses"
     ["/" {:name :user-email-addresses
           :auth-http-safe #{:self :admin}
           :auth-http-unsafe #{:admin}}]
     ["/:email-address"
      ["" {:name :user-email-address
           :auth-http-safe #{:self :admin}
           :auth-http-unsafe #{:admin}}]
      ["/primary" {:name :user-email-address-primary
                   :auth-http-unsafe #{:admin}}]]]
    ["/gpg-keys"
     ["/" {:name :user-gpg-keys
           :auth-http-safe #{:self :admin}
           :auth-http-unsafe #{:self}}]
     ["/:gpg-key-id" {:name :user-gpg-key
                      :auth-http-safe #{:self :admin}
                      :auth-http-unsafe #{:self :admin}}]]]])

(def admin
  ["/admin"
   ["/gpg-keys"
    ["/" {:name :admin-gpg-keys
          :auth-http-safe #{:admin}
          :auth-http-unsafe #{:admin}}]
    ["/:gpg-key-id" {:name :admin-gpg-key
                     :auth-http-safe #{:admin}
                     :auth-http-unsafe #{:admin}}]]])

(def workspace
  ["/commits"
   ["/" {:name :commits
         :auth-http-safe #{:public}}]])

(def routes
  [["/" {:name :root
         :auth-http-unsafe #{}
         :auth-http-safe #{:public}}]
   ["/init" {:name :init
             :no-sign-in-page true
             :auth-http-safe #{:public}
             :auth-http-unsafe #{:public}}]
   admin
   projects
   ["/sign-in" {:auth-http-unsafe #{:public}}
    ["" {:name :sign-in}]
    ["/authenticate/password" {:name :sign-in-authenticate-password}]]
   ["/sign-out" {:name :sign-out
                 :auth-http-unsafe #{:public}
                 :auth-http-safe #{:public}}]
   users
   workspace])

(comment (path :user {:user-id "123"})
         (path :user-password {:user-id "123"}))


(def router (reitit/router routes))

(def routes-flattened (reitit/routes router))


;(reitit/match->path (reitit/match-by-name router :upload {:upload-id 5}))
;(reitit/match-by-path router "/media-service/")

(defn route [path]
  (-> path
      (string/split #"\?")
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
     ([url event & {:keys [reload]
                    :or {reload false}}]
      (if reload
        (set! js/window.location url)
        (navigation/navigate! url event)))))


(comment
  (->> [:user-email-addresses {:user-id "123"}]
       spy
       (apply path)
       spy
       (reitit/match-by-path router)
       spy)

  (->> [:user-email-address {:user-id "123"
                             :email-address "12@abc"}]
       spy
       (apply path)
       spy
       ;(reitit/match-by-path router)
       spy)


  (reitit/match-by-name router :users {:user-id "123"}))
