(ns cider-ci.server.state
  (:require
    [cider-ci.server.html.utils.dom :as dom]
    [cljs.pprint :refer [pprint]]
    [reagent.core :as reagent]
    [timothypratley.patchin :as patchin]
    [taoensso.timbre :refer [debug info warn error spy]])
  (:require-macros
    [reagent.ratom :as ratom :refer [reaction]]))


(def routing* (reagent/atom {}))

(def debug?* (reagent/atom false))

(def server* (reagent/atom {}))

(def user* (reagent/atom nil))

(def state* (reaction
                  {:debug @debug?*
                   :routing @routing*
                   :server @server*
                   :user @user*
                   }))


(defn hidden-routing-state-component
  [& {:keys [did-mount did-change did-update will-unmount]
      :or {did-update #()
           did-change #()
           did-mount #()
           will-unmount #()}}]
  "Invisible react component; fires did-change, did-update, did-change, will-unmount handlers according
  to react handlers and changes in the routing state:
  * did-change on :component-did-mount, :component-did-update or when routing state changed
  * did-mount corresponds to reagent :component-did-mount,
  * did-update corresponds to reagent did-update
  * will-unmount corresponds to reagent :component-will-unmount,
  "
  (let [old-state* (reagent/atom nil)
        eval-did-change (fn [handler args]
                          (let [old-state @old-state*
                                new-state @routing*]
                            (when (not= old-state new-state)
                              (reset! old-state* new-state)
                              (apply handler (concat
                                               [old-state (patchin/diff old-state new-state) new-state]
                                               args)))))]
    (reagent/create-class
      {:component-will-unmount (fn [& args] (apply will-unmount args))
       :component-did-mount (fn [& args]
                              (apply did-mount args)
                              (eval-did-change did-change args))
       :component-did-update (fn [& args]
                               (apply did-update args)
                               (eval-did-change did-change args))
       :reagent-render
       (fn [_]
         [:div.hidden-routing-state-component
          {:style {:display :none}}
          [:pre (with-out-str (pprint @routing*))]])})))

(defn debug-ui-component []
  [:div.debug.state-debug
   (when @debug?*
     [:<>
      [:hr]
      [:h4 "Debug global " [:code "@state*"]]
      [:pre.bg-light
       [:code
        (with-out-str (pprint @state*))
        ]]])])


(defn init []
  (info "initializing state ...")
  (swap! server* merge (dom/data-attribute "body" "server-state"))
  (swap! user* merge (dom/data-attribute "body" "user"))
  (info "initialized state"))
