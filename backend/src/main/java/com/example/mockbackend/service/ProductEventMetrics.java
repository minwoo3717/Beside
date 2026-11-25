package com.example.mockbackend.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Product event meters (docs/METRICS.md §6.5), scraped like the §5 meters on the management port with the common env tag:
 * <ul>
 *   <li>{@code beside.app.events} (name, platform): events stored. Every name × platform is registered at 0.</li>
 *   <li>{@code beside.app.events.dropped} (reason): events dropped (unknown_name, invalid, duplicate, rate_limited) and
 *       prop or field values dropped from a stored event (bad_prop). Every reason is registered at 0.</li>
 * </ul>
 * Tags only ever take these fixed values, so a client cannot create new series.
 */
@Component
public class ProductEventMetrics {
    public static final List<String> DROP_REASONS = List.of("unknown_name", "invalid", "duplicate", "rate_limited", "bad_prop");

    private final Map<String, Counter> stored = new HashMap<>();
    private final Map<String, Counter> dropped = new HashMap<>();

    public ProductEventMetrics(MeterRegistry registry) {
        for (String name : ProductEventCatalog.NAMES) {
            for (String platform : ProductEventCatalog.PLATFORMS) {
                stored.put(name + "|" + platform, Counter.builder("beside.app.events")
                        .description("Product events stored (POST /api/v1/events, docs/METRICS.md §6)")
                        .tags("name", name, "platform", platform)
                        .register(registry));
            }
        }
        for (String reason : DROP_REASONS) {
            dropped.put(reason, Counter.builder("beside.app.events.dropped")
                    .description("Product events dropped by reason; bad_prop counts prop or field values dropped from a stored event")
                    .tag("reason", reason)
                    .register(registry));
        }
    }

    /** One stored event; name and platform have passed the catalog checks. */
    public void stored(String name, String platform) {
        Counter counter = stored.get(name + "|" + platform);
        if (counter != null) {
            counter.increment();
        }
    }

    public void dropped(String reason, int count) {
        Counter counter = dropped.get(reason);
        if (counter != null && count > 0) {
            counter.increment(count);
        }
    }
}
