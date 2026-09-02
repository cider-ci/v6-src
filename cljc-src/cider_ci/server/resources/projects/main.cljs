(ns cider-ci.server.resources.projects.main
  (:refer-clojure :exclude [keyword str])
  (:require
   ["date-fns" :as date-fns]
   ["react-bootstrap" :as bs]
   [cider-ci.utils.core :refer [str keyword]]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state :refer [routing*] :rename {routing* routing-state*}]
   [cljs.pprint :refer [pprint]]
   [reagent.core :as reagent :refer [reaction]]
   [taoensso.timbre :refer [debug info warn error spy]]))


(defonce data* (reagent/atom {}))


(defn fetch-td-component [params]
  (let [ctx-classes (case (some-> params :state)
                      "ok" ["success" "table-success"]
                      ["table-warning"])]
    [:td.fetch
     {:class ctx-classes}
     [:<>
      (when-let [last-fetched-at (some-> params :last_fetched_at (js/Date.))]
        [:span (date-fns/formatDistance
                last-fetched-at (js/Date.) (clj->js {:addSuffix true}))])]]))


(defn projects-component []
  [:div.projects
   [:div.d-flex.align-items-center.justify-content-between.mb-3
    [:h2 [icons/projects] " Projects"]
    (when (-> @state/user* :is_admin)
      [:a.btn.btn-primary {:href (path :project-new)}
       [icons/create] " New Project"])]
   [state/hidden-routing-state-component
    :did-change #(http-client/route-cached-fetch
                  data* :reload true :reload-delay 500)]

   [:<> (when @state/debug?*
          [:div.pre (with-out-str (pprint @data*))])]

   (if-not (contains? @data* (:route @routing-state*))
     [:div "Loading..."]
     (let [projects (seq (get @data* (:route @routing-state*)))]
       (if-not projects
         [:p.text-muted "No projects yet."]
         [:table.table.table-sm.table-striped.projects
          [:thead
           [:tr
            [:th "ID"]
            [:th "Name"]
            [:th "Fetch"]]]
          [:tbody
           (for [project projects]
             ^{:key (:id project)}
             [:tr.project
              [:td.id [:a {:href (path :project {:project-id (:id project)})}
                       (:id project)]]
              [:td.name [:a {:href (path :project {:project-id (:id project)})}
                         (:name project)]]
              [:<> (fetch-td-component (:fetch-and-update project))]])]])))])


(defn page []
  [:div.page
   [projects-component]])

(def components {:page page})
