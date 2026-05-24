package name.jurgenei.gradle.antlr.catalog;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory grammar catalog keyed by grammar name.
 */
public final class GrammarCatalog {

    private final Map<String, GrammarCatalogEntry> entries;

    /**
     * Creates a catalog from grammar entries keyed by grammar name.
     *
     * @param entries catalog entries keyed by grammar name.
     */
    public GrammarCatalog(final Map<String, GrammarCatalogEntry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * Returns a grammar entry by name.
     *
     * @param name grammar name.
     * @return matching catalog entry.
     * @throws IllegalArgumentException when the grammar name is not present.
     */
    public GrammarCatalogEntry require(final String name) {
        final GrammarCatalogEntry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Grammar '" + name + "' was not found in catalog");
        }
        return entry;
    }

    /**
     * Returns all catalog entries preserving load order.
     *
     * @return catalog entries.
     */
    public Collection<GrammarCatalogEntry> values() {
        return entries.values();
    }
}

