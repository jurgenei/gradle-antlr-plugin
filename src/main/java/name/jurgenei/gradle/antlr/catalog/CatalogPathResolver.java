package name.jurgenei.gradle.antlr.catalog;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * Resolves local path and URL grammar coordinates declared in a catalog entry.
 */
public final class CatalogPathResolver {

    private CatalogPathResolver() {
    }

    /**
     * Resolves a catalog coordinate to a URI.
     *
     * <p>Absolute URIs are returned as-is. Non-absolute values are treated as paths
     * relative to {@code baseDirectory}.</p>
     *
     * @param value catalog value (path or URI).
     * @param baseDirectory base directory for relative values.
     * @return resolved URI.
     */
    public static URI resolveToUri(final String value, final Path baseDirectory) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Catalog value must not be blank");
        }

        final String trimmed = value.trim();
        try {
            final URI uri = new URI(trimmed);
            if (uri.isAbsolute()) {
                return uri;
            }
        } catch (URISyntaxException ignored) {
            // Fall back to local path handling.
        }

        return baseDirectory.resolve(trimmed).normalize().toUri();
    }

    /**
     * Resolves a catalog coordinate to a local file system path when possible.
     *
     * @param value catalog value (path or URI).
     * @param baseDirectory base directory for relative values.
     * @return resolved local path, or {@code null} for non-file URIs.
     */
    public static Path resolveToPath(final String value, final Path baseDirectory) {
        final URI resolved = resolveToUri(value, baseDirectory);
        if (resolved.getScheme() != null && !"file".equalsIgnoreCase(resolved.getScheme())) {
            return null;
        }
        return Path.of(resolved);
    }
}

