package com.example.causal.bench.jmh;

import com.example.causal.bench.db.QueryTimeout;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Timeout bookkeeping for the DBMS baselines. Every timed-out query is appended to
 * the CSV named by -Dbench.timeouts (system,dataset,operator,args). After
 * {@link #SKIP_AFTER} timeouts an operator is censored for the rest of the trial:
 * further invocations are recorded as timeouts without running, and the result
 * scripts draw any (system, dataset, operator) with a timeout as a censored point
 * at 60 s instead of using its JMH samples.
 */
final class Censor {

    static final int SKIP_AFTER = 3;

    private final String system, dataset;
    private final Map<String, Integer> timeouts = new HashMap<>();

    Censor(String system, String dataset) {
        this.system = system;
        this.dataset = dataset;
    }

    boolean censored(String operator) {
        return timeouts.getOrDefault(operator, 0) >= SKIP_AFTER;
    }

    long record(String operator, String args, long result) {
        if (result == QueryTimeout.TIMEOUT || censored(operator)) {
            timeouts.merge(operator, 1, Integer::sum);
            String file = System.getProperty("bench.timeouts");
            if (file != null) {
                try {
                    Path p = Path.of(file);
                    if (!Files.exists(p)) Files.writeString(p, "system,dataset,operator,args,skipped\n");
                    Files.writeString(p, String.join(",", system, dataset, operator, args.replace(',', ';'),
                            String.valueOf(result != QueryTimeout.TIMEOUT)) + "\n", StandardOpenOption.APPEND);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return QueryTimeout.TIMEOUT;
        }
        return result;
    }
}
