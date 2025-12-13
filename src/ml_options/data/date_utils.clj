(ns ml-options.data.date-utils
  "Date and time utilities for options data ingestion.

  Shared functions for converting between LocalDate and Instant
  in the America/New_York timezone (trading hours)."
  (:import [java.time LocalDate Instant ZoneId DayOfWeek]))

(defn local-date->eod-instant
  "Convert LocalDate to EOD Instant (5pm ET = end of trading day).

  In EST (winter): 5pm ET = 22:00 UTC
  In EDT (summer): 5pm ET = 21:00 UTC

  Args:
    date - java.time.LocalDate

  Returns:
    java.time.Instant at 5pm ET on the given date"
  [^LocalDate date]
  (-> date
      (.atTime 17 0)  ; 5pm
      (.atZone (ZoneId/of "America/New_York"))
      .toInstant))

(defn instant->local-date
  "Convert Instant to LocalDate in ET timezone.

  Args:
    inst - java.time.Instant

  Returns:
    java.time.LocalDate in America/New_York timezone"
  [^Instant inst]
  (-> inst
      (.atZone (ZoneId/of "America/New_York"))
      .toLocalDate))

(defn weekend?
  "Check if a LocalDate falls on a weekend (Saturday or Sunday).

  Args:
    date - java.time.LocalDate

  Returns:
    true if date is Saturday or Sunday, false otherwise"
  [^LocalDate date]
  (let [dow (.getDayOfWeek date)]
    (or (= dow DayOfWeek/SATURDAY)
        (= dow DayOfWeek/SUNDAY))))
