(ns cider-ci.server.html.avatar
  "The avatar image shown in place of a person's name, with the name in a
   tooltip. The image comes from /users/:id/avatar (the user's image, else
   an identicon) or from /avatars/by-email/:hash for commit authors and
   committers (the owner's image, else an identicon of the address)."
  (:require
    ["react-bootstrap" :as bs]
    [cider-ci.server.routes :refer [path]]
    [clojure.string :as str]
    [reagent.core :as reagent]))


(defn- tooltip-lines [{:keys [name login email admin? note]}]
  (remove str/blank?
          [name
           (when login (str "@" login))
           email
           (when admin? "Admin")
           note]))

(defn avatar
  "One of :user-id or :email-hash (md5 of the lower-cased address) picks the
   image. :name, :login, :email, :admin? and :note fill the tooltip; the alt
   text (:alt, else name, login or email) is what screen readers, specs and
   a broken image show. :size in px (default 24); :href wraps a link;
   :placement of the tooltip (default bottom), :tooltip false for none."
  [{:keys [user-id email-hash alt size href class placement tooltip] :as opts}]
  (let [size  (or size 24)
        src   (cond
                user-id    (path :user-avatar {:user-id user-id})
                email-hash (path :avatar-by-email {:email-hash email-hash}))
        lines (tooltip-lines opts)
        label (or alt (first lines) "?")
        img   [:img.avatar.rounded-circle
               {:src src :alt label :width size :height size
                :class class :data-user-id user-id}]]
    (when src
      (let [inner (if href [:a.avatar-link {:href href} img] img)]
        (if (false? tooltip)
          inner
          [:> bs/OverlayTrigger
           {:placement (or placement "bottom")
            :overlay (reagent/as-element
                       [:> bs/Tooltip {:id (str "avatar-tip-" (or user-id email-hash))}
                        (into [:<>] (interpose [:br] (seq lines)))])}
           inner])))))

(defn user-avatar
  "The avatar of a user row ({:id :name :login :github_login
   :email_addresses :is_admin}), linked to the user's page unless :href is
   given (false for no link)."
  [{:keys [id name login github_login email_addresses is_admin]}
   & {:keys [size href alt placement tooltip]}]
  [avatar {:user-id   id
           :name      name
           :login     (or login github_login)
           :email     (when (string? (first email_addresses)) (first email_addresses))
           :admin?    is_admin
           :alt       (or alt login github_login)
           :size      size
           :href      (if (nil? href) (path :user {:user-id id}) href)
           :placement placement
           :tooltip   tooltip}])

(defn email-avatar
  "The avatar for an email address with its md5 hash (commit author or
   committer): the account's image when the address belongs to one."
  [{:keys [email-hash name email]} & {:keys [size]}]
  [avatar {:email-hash email-hash :name name :email email :alt name :size size}])
