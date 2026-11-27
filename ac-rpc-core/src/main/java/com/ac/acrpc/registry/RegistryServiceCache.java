package com.ac.acrpc.registry;

import com.ac.acrpc.model.ServiceMetaInfo;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Consumer-side cache of discovered providers, keyed by service key.
 *
 * <p>Each entry carries a generation number. {@link #invalidate} bumps it, and {@link #write}
 * only stores a list fetched under the current generation, so a watch event that arrives while a
 * lookup is in flight cannot be overwritten by that lookup's older result.
 *
 * <p>Invalidated entries keep their last known list as a stale fallback: if the registry cannot
 * be reached, callers can keep using the providers they last saw instead of failing every call.
 */
public class RegistryServiceCache {

    private record Entry(List<ServiceMetaInfo> nodes, long generation, boolean fresh) {
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    /**
     * Fresh nodes for the service, or null if it must be looked up.
     */
    public List<ServiceMetaInfo> read(String serviceKey) {
        Entry entry = entries.get(serviceKey);
        return entry != null && entry.fresh() ? entry.nodes() : null;
    }

    /**
     * Last known nodes for the service, fresh or not, or null if it was never looked up.
     */
    public List<ServiceMetaInfo> readStale(String serviceKey) {
        Entry entry = entries.get(serviceKey);
        return entry == null ? null : entry.nodes();
    }

    /**
     * Generation to pass to {@link #write}; read it before starting the lookup.
     */
    public long generation(String serviceKey) {
        Entry entry = entries.get(serviceKey);
        return entry == null ? 0 : entry.generation();
    }

    /**
     * Stores a lookup result unless the entry was invalidated after {@code generation} was read.
     */
    public void write(String serviceKey, List<ServiceMetaInfo> nodes, long generation) {
        List<ServiceMetaInfo> snapshot = List.copyOf(nodes);
        entries.compute(serviceKey, (key, current) -> {
            long currentGeneration = current == null ? 0 : current.generation();
            if (currentGeneration != generation) {
                return current;
            }
            return new Entry(snapshot, generation, true);
        });
    }

    /**
     * Marks the service's nodes as out of date; the next read triggers a lookup.
     */
    public void invalidate(String serviceKey) {
        entries.compute(serviceKey, (key, current) -> current == null
                ? new Entry(null, 1, false)
                : new Entry(current.nodes(), current.generation() + 1, false));
    }

    public void clear() {
        entries.clear();
    }
}
