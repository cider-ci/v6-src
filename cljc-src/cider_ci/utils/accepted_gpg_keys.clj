(ns cider-ci.utils.accepted-gpg-keys
  "Which commit signatures an executor accepts trials for: a list of public
   GPG keys (ASCII armored). With keys the executor only runs trials of
   commits signed with one of them; an empty list (or no list) accepts any
   commit, signed or not.

   The executor reports its keys as entries {:fingerprint (primary key)
   :fingerprints (primary and subkeys) :key_id :user_ids} on every sync; the
   server stores them and only dispatches matching trials
   (`cider_ci_signature_accepted` in SQL, by the commit's verified
   signature_fingerprint). The executor verifies the signature itself before
   anything runs, with the keys as the only trust anchor. A key that cannot
   be parsed becomes {:error ...} without fingerprints: it matches nothing
   (fail closed) and is shown on the executor's page.

   Only GPG signatures are covered, not SSH signatures. See
   docs/executors/accepted-gpg-keys.md."
  (:require
    [cider-ci.utils.git-gpg :as git-gpg]
    [clojure.string :as str]
    [taoensso.timbre :refer [warn]]))


(defn- key-entries
  "One entry per primary key of the armored key (ring), with the fingerprints
   of the primary key and its subkeys."
  [ascii-key]
  (->> (git-gpg/pub-keys ascii-key)
       (partition-by #(.isMasterKey %))
       (partition-all 2)
       (keep (fn [[[master] subkeys]]
               (when (.isMasterKey master)
                 {:fingerprint  (git-gpg/hex-fingerprint master)
                  :fingerprints (mapv git-gpg/hex-fingerprint (cons master subkeys))
                  :key_id       (format "%016X" (.getKeyID master))
                  :user_ids     (vec (iterator-seq (.getUserIDs master)))})))))

(defn parse-keys
  "Entries for a list of ASCII armored public keys; a key that cannot be
   parsed becomes {:error message} (never matches)."
  [ascii-keys]
  (vec (mapcat (fn [ascii-key]
                 (try
                   (let [entries (key-entries (str ascii-key))]
                     (if (seq entries)
                       entries
                       [{:error "no public key found"}]))
                   (catch Exception e
                     (warn "Cannot parse an accepted GPG key:" (.getMessage e))
                     [{:error (str "cannot parse the key: " (.getMessage e))}])))
               ascii-keys)))

(defn normalize-entries
  "Entries as reported by an executor (string keys from JSON allowed), with
   uppercase fingerprints; everything else is dropped."
  [entries]
  (->> entries
       (filter map?)
       (mapv (fn [e]
               (let [g #(or (get e %) (get e (name %)))
                     fps (->> (or (g :fingerprints) []) (map (comp str/upper-case str)) vec)]
                 (cond-> {:fingerprints fps}
                   (g :fingerprint) (assoc :fingerprint (str/upper-case (str (g :fingerprint))))
                   (g :key_id)      (assoc :key_id (str (g :key_id)))
                   (g :user_ids)    (assoc :user_ids (mapv str (g :user_ids)))
                   (g :error)       (assoc :error (str (g :error)))))))))

(defn accept-all? [entries]
  (empty? entries))

(defn signature-accepted?
  "Whether a commit verified by fingerprint (nil: unsigned or not verified)
   is accepted; same rule as `cider_ci_signature_accepted`."
  [entries fingerprint]
  (or (accept-all? entries)
      (boolean (and fingerprint
                    (some #(some #{(str/upper-case fingerprint)} (:fingerprints %)) entries)))))

(defn verified-fingerprint
  "The fingerprint of the accepted key that signed the commit (`git cat-file
   -p` output), or nil."
  [ascii-keys cat-file-commit]
  (some (fn [ascii-key]
          (try (git-gpg/valid-signature-fingerprint cat-file-commit ascii-key)
               (catch Exception e
                 (warn "Signature check against an accepted GPG key failed:" (.getMessage e))
                 nil)))
        ascii-keys))

(defn assert-signed!
  "Throws unless the commit is signed with one of the accepted keys; with no
   keys every commit is accepted."
  [ascii-keys cat-file-commit commit-id]
  (when (seq ascii-keys)
    (or (verified-fingerprint ascii-keys cat-file-commit)
        (throw (ex-info (str "The commit " commit-id " is not signed with an accepted GPG key of this executor"
                             (if (git-gpg/extract-ascii-commit-signature cat-file-commit)
                               " (signed with another key)."
                               " (unsigned)."))
                        {:commit-id commit-id ::rejected true})))))

(defn rejected? [e]
  (boolean (::rejected (ex-data e))))
