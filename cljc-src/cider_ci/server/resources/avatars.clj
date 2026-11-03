(ns cider-ci.server.resources.avatars
  "The avatar images shown in place of user names.

   /users/:user-id/avatar is the user's stored image (imported from GitHub
   at sign-in, table user_images), else an identicon generated from the
   MD5 of the lower-cased primary email address, else from the user id.

   /avatars/by-email/:email-hash takes the MD5 of a lower-cased email
   address (as Gravatar does) and serves the avatar of the user who owns
   that address, else an identicon of the hash: commit authors and
   committers are shown this way, whether they have an account or not."
  (:require
    [cider-ci.utils.identicon :as identicon]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error]]))


(def ^:private cache-control "private, max-age=3600")

(defn- etag [s] (str "\"" s "\""))

(defn- with-etag
  "304 when the client has this version already."
  [request tag response]
  (if (= tag (get-in request [:headers "if-none-match"]))
    {:status 304 :headers {"ETag" tag "Cache-Control" cache-control}}
    (assoc-in response [:headers "ETag"] tag)))

(defn- image-response [request {:keys [content_type data updated_at]}]
  (with-etag request (etag (identicon/md5-hex (str updated_at)))
    {:status 200
     :headers {"Content-Type" content_type "Cache-Control" cache-control}
     :body data}))

(defn- identicon-response [request hash]
  (with-etag request (etag (str "identicon-" hash))
    {:status 200
     :headers {"Content-Type" "image/svg+xml" "Cache-Control" cache-control}
     :body (identicon/svg hash)}))

(def ^:private not-found {:status 404 :body "Not Found"})


(defn- uuid? [s] (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" (str s)))

(defn- user-exists? [tx user-id]
  (jdbc/execute-one! tx ["SELECT 1 FROM users WHERE id = ?::uuid" user-id]))

(defn- user-image [tx user-id]
  (jdbc/execute-one! tx ["SELECT content_type, data, updated_at
                          FROM user_images WHERE user_id = ?::uuid" user-id]))

(defn- user-identicon-hash
  "The primary (else oldest) email address decides the identicon, so a
   commit author with that address gets the same image as the user."
  [tx user-id]
  (or (:hash (jdbc/execute-one! tx ["SELECT md5(lower(email_address)) AS hash
                                     FROM email_addresses WHERE user_id = ?::uuid
                                     ORDER BY is_primary DESC, created_at ASC LIMIT 1" user-id]))
      (identicon/md5-hex (str user-id))))

(defn- user-avatar [request tx user-id]
  (cond
    (not (uuid? user-id)) not-found
    (not (user-exists? tx user-id)) not-found
    :else (if-let [image (user-image tx user-id)]
            (image-response request image)
            (identicon-response request (user-identicon-hash tx user-id)))))

(defn- avatar-by-email [request tx email-hash]
  (if-not (identicon/valid-hash? email-hash)
    not-found
    (if-let [user-id (:user_id (jdbc/execute-one!
                                 tx ["SELECT user_id FROM email_addresses
                                      WHERE md5(lower(email_address)) = ?" email-hash]))]
      (user-avatar request tx (str user-id))
      (identicon-response request email-hash))))


(defn handler [{{{route-name :name} :data {:keys [user-id email-hash]} :path-params} :route
                method :request-method tx :tx :as request}]
  (if (not= :get method)
    {:status 405 :body "Method not allowed"}
    (case route-name
      :user-avatar     (user-avatar request tx user-id)
      :avatar-by-email (avatar-by-email request tx email-hash))))
