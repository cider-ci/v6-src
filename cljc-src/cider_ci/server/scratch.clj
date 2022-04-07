(ns cider_ci.server.scratch
  (:require
    [cider-ci.server.db.core :as db]
    [honey.sql :refer [format] :rename {format sql-format}]
    [honey.sql.helpers :as sql]
    [next.jdbc :as jdbc]
    [taoensso.timbre :refer [debug info warn error spy]]))



; SELECT users.*,
;   (SELECT json_agg(email_addresses.email
;                    ORDER BY is_primary DESC, email DESC)
;    FROM email_addresses
;    WHERE email_addresses.user_id = users.id
;    GROUP BY user_id) AS email_addresses
; FROM users;
;





