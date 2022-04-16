(ns cider-ci.server.http.core)

(def ANTI_CRSF_TOKEN_COOKIE_NAME "cider-ci-anti-csrf-token")
(def HTTP_UNSAVE_METHODS #{:delete :patch :post :put})
(def HTTP_SAVE_METHODS #{:get :head :options :trace})
