(ns cider-ci.server.resources.projects.configuration
  (:require
   ["highlight.js/lib/core" :as hljs]
   ["highlight.js/lib/languages/yaml" :as hljs-yaml]
   ["js-yaml" :as yaml]
   [cider-ci.server.html.clipboard :as clipboard]
   [cider-ci.server.html.icons :as icons]
   [cider-ci.server.http.client.main :as http-client]
   [cider-ci.server.routes :refer [path]]
   [cider-ci.server.state :as state]
   [reagent.core :as reagent]))

(.registerLanguage hljs "yaml" hljs-yaml)

(defonce _data* (reagent/atom {}))

(def data* (reagent/reaction (get @_data* (:route @state/routing*))))


(defn- fetch-data [& _]
  (http-client/route-cached-fetch _data*))


(defn- project-id []
  (-> @state/routing* :path-params :project-id))

(defn- commit-id []
  (-> @state/routing* :path-params :commit-id))


(defn- as-yaml [data]
  (try
    (yaml/dump (clj->js data) #js {:indent 2})
    (catch :default _ nil)))

(defn- highlight-yaml [yaml-str]
  (try
    (-> (.highlight hljs yaml-str #js {:language "yaml"}) .-value)
    (catch :default _ nil)))

(defn- code-block [initial-html]
  (let [state #js {:node nil :html initial-html}]
    (reagent/create-class
      {:component-did-mount
       (fn [_]
         (when-let [node (.-node state)]
           (set! (.-innerHTML node) (.-html state))))
       :component-did-update
       (fn [_]
         (when-let [node (.-node state)]
           (set! (.-innerHTML node) (.-html state))))
       :reagent-render
       (fn [html]
         (set! (.-html state) html)
         [:code.hljs {:ref #(set! (.-node state) %)}])})))


(defn page []
  [:div.page.configuration
   [state/hidden-routing-state-component :did-change #(fetch-data)]
   (if-not (some? @data*)
     [:div "Loading..."]
     [:<>
      [:nav.mb-3
       [:a {:href (path :project {:project-id (project-id)})}
        [icons/projects] " " (project-id)]
       " / "
       [:a {:href (path :project-commit {:project-id (project-id) :commit-id (commit-id)})}
        [:code (subs (commit-id) 0 8)]]
       " / Configuration"]
      (if (string? @data*)
        [:div.alert.alert-warning @data*]
        (let [yml  (as-yaml @data*)
              html (when yml (highlight-yaml yml))]
          [:<>
           [:div.d-flex.align-items-center.gap-2.mb-2
            [:h3.mb-0 "Generated Configuration"]
            (when yml [clipboard/button yml])]
           [:p.text-muted
            "Resolved " [:code "cider-ci.yml"]
            " — includes expanded, templates applied."]
           (if html
             [:pre.p-0.mb-0 [code-block html]]
             [:pre.bg-light.p-3 yml])]))])])


(def components {:page page})
