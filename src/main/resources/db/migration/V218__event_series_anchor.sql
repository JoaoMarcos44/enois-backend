-- Keep the calendar anchor in every occurrence, independently of event retention.
-- DATE adds no character data and leaves the table's charset/collation unchanged.
alter table events add column if not exists series_anchor_date date null;

-- Existing series use their earliest surviving readable date (ISO or Brazilian format).
-- Recurring schedules were validated by the application before being stored.
-- If the original occurrence is already gone, this cannot recover an original day such as 31
-- from a surviving February 28/29. One-off events without a series remain null.
update events e set series_anchor_date = (
    select min(case
        when known.date regexp '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then cast(known.date as date)
        when known.date regexp '^[0-9]{2}/[0-9]{2}/[0-9]{4}$' then
            cast(concat(substring(known.date, 7, 4), '-', substring(known.date, 4, 2), '-',
                substring(known.date, 1, 2)) as date)
    end)
    from events known where known.series_id = e.series_id
) where e.series_id is not null and e.series_anchor_date is null;
