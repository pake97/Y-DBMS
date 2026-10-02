package com.example.causal.bench.run;

import com.example.causal.bench.data.Datasets;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Writes environment.json for a run directory: hardware, OS, JVM, Neo4j/GDS versions, git state. */
public final class Environment {

    /** Heap given to every JMH fork (see the @Fork annotations). */
    public static final String FORK_XMX = "4g";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Properties versions = new Properties();
        try (InputStream in = Environment.class.getResourceAsStream("/bench-versions.properties")) {
            versions.load(in);
        }
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        ObjectNode e = Datasets.JSON.createObjectNode();
        e.put("cpu_model", cpuModel());
        e.put("cpu_count", Runtime.getRuntime().availableProcessors());
        e.put("ram_bytes", os.getTotalMemorySize());
        e.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        e.put("jvm_vendor", System.getProperty("java.vendor"));
        e.put("jvm_version", System.getProperty("java.vm.version"));
        e.put("jmh_fork_xmx", FORK_XMX);
        e.put("driver_max_heap_bytes", Runtime.getRuntime().maxMemory());
        e.put("neo4j_version", versions.getProperty("neo4j.version"));
        e.put("gds_version", versions.getProperty("gds.version"));
        e.put("gds_mode", "embedded");
        e.put("jmh_version", versions.getProperty("jmh.version"));
        e.put("git_commit", sh("git", "rev-parse", "HEAD"));
        e.put("git_dirty", !sh("git", "status", "--porcelain", "--", "plugin", "bench/src", "bench/pom.xml", "bench/py").isEmpty());
        Files.createDirectories(out.getParent());
        Datasets.JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), e);
    }

    private static String cpuModel() throws IOException, InterruptedException {
        if (System.getProperty("os.name").toLowerCase().contains("mac")) return sh("sysctl", "-n", "machdep.cpu.brand_string");
        Path info = Path.of("/proc/cpuinfo");
        if (Files.exists(info)) {
            for (String line : Files.readAllLines(info)) {
                if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
            }
        }
        return "unknown";
    }

    private static String sh(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(Datasets.root().toFile()).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes()).trim();
        return p.waitFor() == 0 ? s : "unknown";
    }
}
