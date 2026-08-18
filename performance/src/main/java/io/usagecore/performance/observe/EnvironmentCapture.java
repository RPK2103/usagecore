package io.usagecore.performance.observe;

import java.lang.management.ManagementFactory;
import java.util.Locale;

public final class EnvironmentCapture {

    private EnvironmentCapture() {
    }

    public static void main(String[] args) {
        Runtime runtime = Runtime.getRuntime();
        System.out.println("os.name=" + System.getProperty("os.name"));
        System.out.println("os.version=" + System.getProperty("os.version"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("availableProcessors=" + runtime.availableProcessors());
        System.out.println("jvm.maxMemoryBytes=" + runtime.maxMemory());
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.vendor=" + System.getProperty("java.vendor"));
        System.out.println("java.home=" + System.getProperty("java.home"));
        System.out.println("user.timezone=" + System.getProperty("user.timezone"));
        System.out.println("locale=" + Locale.getDefault());
        System.out.println("runtimeMx.vmName=" + ManagementFactory.getRuntimeMXBean().getVmName());
        System.out.println("runtimeMx.vmVersion=" + ManagementFactory.getRuntimeMXBean().getVmVersion());
        System.out.println("note=Docker Desktop / container CPU-memory limits are not visible to this JVM; record them from docker inspect if configured.");
    }
}
