package io.usagecore.performance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class LabJdbc {

    private LabJdbc() {
    }

    public static Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", PerformanceSettings.jdbcUser());
        props.setProperty("password", PerformanceSettings.jdbcPassword());
        props.setProperty("ApplicationName", "usagecore-performance-lab");
        return DriverManager.getConnection(PerformanceSettings.jdbcUrl(), props);
    }
}
