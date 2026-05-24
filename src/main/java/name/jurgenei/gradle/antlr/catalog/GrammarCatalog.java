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

    public GrammarCatalog(final Map<String, GrammarCatalogEntry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public GrammarCatalogEntry require(final String name) {
        final GrammarCatalogEntry entry = entries.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Grammar '" + name + "' was not found in catalog");
        }
        return entry;
    }

    public Collection<GrammarCatalogEntry> values() {
        return entries.values();
    }
}

