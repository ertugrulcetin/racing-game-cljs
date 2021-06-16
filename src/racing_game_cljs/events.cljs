(ns racing-game-cljs.events
  (:require
    [day8.re-frame.tracing :refer-macros [fn-traced]]
    [racing-game-cljs.db :as db]
    [re-frame.core :refer [reg-event-db reg-event-fx]]))

(reg-event-db
  ::initialize-db
  (fn-traced [_ _]
             db/default-db))

(reg-event-db
  ::set-control
  (fn-traced [db [_ type pressed?]]
             (assoc-in db [:controls type] pressed?)))
