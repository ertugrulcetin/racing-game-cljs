(ns racing-game-cljs.subs
  (:require
    [re-frame.core :refer [reg-sub]]))

(reg-sub
  ::controls
  (fn [db]
    (:controls db)))
