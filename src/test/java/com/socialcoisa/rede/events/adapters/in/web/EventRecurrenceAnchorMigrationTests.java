package com.socialcoisa.rede.events.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class EventRecurrenceAnchorMigrationTests {

    @Test
    void backfillsBestAvailableDateAndLeavesOneOffEventsAlone() throws Exception {
        String url = "jdbc:h2:mem:event_anchor_migration;MODE=MySQL";
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var statement = connection.createStatement()) {
            statement.execute("create table events (id varchar(255), series_id varchar(255), date varchar(255))");
            statement.execute("insert into events values ('root','root','31/01/2027'),"
                + "('child','root','2027-02-28'),('survivor','gone','2028-02-29'),"
                + "('later','gone','2028-03-29'),('once',null,'2027-01-31')");
            var migration = new ClassPathResource("db/migration/V218__event_series_anchor.sql");
            ScriptUtils.executeSqlScript(connection, migration);
            ScriptUtils.executeSqlScript(connection, migration);
            try (var rows = statement.executeQuery("select id, series_anchor_date from events order by id")) {
                var anchors = new java.util.HashMap<String, String>();
                while (rows.next()) {
                    anchors.put(rows.getString(1), rows.getString(2));
                }
                assertThat(anchors).containsEntry("root", "2027-01-31").containsEntry("child", "2027-01-31")
                    .containsEntry("survivor", "2028-02-29").containsEntry("later", "2028-02-29")
                    .containsEntry("once", null);
            }
        }
    }
}
