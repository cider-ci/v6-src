(ns cider-ci.server.html.breadcrumbs
  "The page's position in the tree (legacy parity: the old CI's tree nav):
   derived from the route and its parameters, every item a link. Pages that
   know a nicer name for an id (job key, executor name, user login) register
   it with label!; a page whose route lacks the context (the trial page)
   sets the whole chain with override!."
  (:require
    [cider-ci.server.html.icons :as icons]
    [cider-ci.server.routes :refer [path]]
    [cider-ci.server.state :as state]
    [reagent.core :as reagent]))


(defonce ^:private labels*    (reagent/atom {}))   ; {param {value label}}
(defonce ^:private overrides* (reagent/atom {}))   ; {route crumbs}

(defn label!
  "Registers text as the label of the path parameter value (e.g. the job
   key for a job id). Safe to call while rendering: no-op when unchanged."
  [param value text]
  (when (and value text (not= text (get-in @labels* [param value])))
    (swap! labels* assoc-in [param value] text))
  nil)

(defn override!
  "Replaces the crumbs of the current route (same contract as crumbs)."
  [crumbs]
  (let [route (:route @state/routing*)]
    (when (not= crumbs (get @overrides* route))
      (swap! overrides* assoc route crumbs)))
  nil)

(defn- lbl [param value default] (get-in @labels* [param value] default))
(defn- shorten [s] (when s (subs s 0 (min 8 (count s)))))

(defn crumbs
  "[{:label :href :icon :code?} ...] for the routing state, root first."
  [{:keys [name path-params]}]
  (let [{:keys [project-id commit-id job-id task-id branch-name blob-path
                user-id executor-id gpg-key-id trial-id]} path-params
        projects  [{:label "Projects" :href (path :projects) :icon :projects}]
        project   (conj projects {:label project-id :href (path :project {:project-id project-id})})
        commit    (conj project {:label (shorten commit-id) :code? true
                                 :href (path :project-commit {:project-id project-id :commit-id commit-id})})
        jobs      (conj commit {:label "Jobs" :href (path :project-jobs {:project-id project-id :commit-id commit-id})})
        job       (conj jobs {:label (lbl :job-id job-id (shorten job-id)) :code? true
                              :href (path :project-job {:project-id project-id :commit-id commit-id :job-id job-id})})
        users     [{:label "Users" :href (path :users) :icon :users}]
        user      (conj users {:label (lbl :user-id user-id user-id) :href (path :user {:user-id user-id})})
        executors [{:label "Executors" :href (path :executors {}) :icon :server}]
        executor  (conj executors {:label (lbl :executor-id executor-id executor-id)
                                   :href (path :executor-detail {:executor-id executor-id})})
        admin     [{:label "Admin"}]]
    (case name
      :projects                     projects
      :project-new                  (conj projects {:label "New"})
      :project                      project
      :project-edit                 (conj project {:label "Edit"})
      :project-branch               (conj project {:label branch-name :icon :code-branch})
      :project-commit               commit
      :project-commit-configuration (conj commit {:label "Configuration"})
      :project-blob                 (conj commit {:label blob-path :code? true})
      :project-jobs                 jobs
      :project-jobs-graph           (conj jobs {:label "Dependencies"})
      :project-job                  job
      :project-job-task             (conj job {:label (lbl :task-id task-id (shorten task-id)) :code? true})
      :users                        users
      :user-new                     (conj users {:label "New"})
      :user                         user
      :user-edit                    (conj user {:label "Edit"})
      :user-password                (conj user {:label "Password"})
      :user-email-addresses         (conj user {:label "Email addresses"})
      :user-gpg-keys                (conj user {:label "GPG keys"})
      :user-gpg-key                 (conj user {:label "GPG keys" :href (path :user-gpg-keys {:user-id user-id})}
                                          {:label (lbl :gpg-key-id gpg-key-id (shorten gpg-key-id))})
      :executors                    executors
      :executor-new                 (conj executors {:label "New"})
      :executor-detail              executor
      :executor-edit                (conj executor {:label "Edit"})
      :admin-settings               (conj admin {:label "Settings" :href (path :admin-settings)})
      :admin-settings-edit          (conj admin {:label "Settings" :href (path :admin-settings)} {:label "Edit"})
      :admin-gpg-keys               (conj admin {:label "Global GPG keys" :href (path :admin-gpg-keys {})})
      :admin-gpg-key                (conj admin {:label "Global GPG keys" :href (path :admin-gpg-keys {})}
                                          {:label (lbl :gpg-key-id gpg-key-id (shorten gpg-key-id))})
      :commits                      [{:label "Commits" :href (path :commits) :icon :commits}]
      :jobs                         [{:label "Jobs" :href (path :jobs) :icon :jobs}]
      :scheduled                    [{:label "Scheduled" :href (path :scheduled) :icon :clock}]
      :trial                        [{:label "Trials"} {:label (shorten trial-id) :code? true}]
      nil)))

(def ^:private icon-fns
  {:projects icons/projects :users icons/users :server icons/server :commits icons/commits
   :jobs icons/jobs :clock icons/clock :code-branch icons/code-branch})

(defn component []
  (let [routing @state/routing*
        items   (or (get @overrides* (:route routing)) (crumbs routing))]
    (when (seq items)
      [:ol.breadcrumb.mb-0.tree-nav
       (for [[i {:keys [label href icon code?]}] (map-indexed vector items)
             :let [last?   (= i (dec (count items)))
                   content [:<> (when-let [f (icon-fns icon)] [:<> [f] " "])
                            (if code? [:code label] label)]]]
         ^{:key i}
         [:li.breadcrumb-item {:class (when last? "active") :aria-current (when last? "page")}
          (if href [:a {:href href} content] content)])])))
