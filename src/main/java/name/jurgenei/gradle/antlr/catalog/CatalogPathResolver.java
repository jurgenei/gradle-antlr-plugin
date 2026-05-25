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
     * <p>Protocol-relative coordinates (`//host/path.g4`) and host/path coordinates without
     * explicit scheme (`localhost:8080/MiniParser.g4`, `example.org/Parser.g4`) are also
     * supported. The default scheme is `http` for localhost/loopback hosts and `https`
     * otherwise.</p>
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

        if (trimmed.startsWith("//")) {
            return URI.create("https:" + trimmed);
        }

        if (looksLikeHostPathWithoutScheme(trimmed)) {
            return URI.create(defaultSchemeForHostPath(trimmed) + "://" + trimmed);
        }

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

    private static boolean looksLikeHostPathWithoutScheme(final String value) {
        if (!value.contains("/") || value.startsWith(".") || value.startsWith("/")) {
            return false;
        }
        final int slash = value.indexOf('/');
        final String hostPort = value.substring(0, slash);
        if (hostPort.contains("://") || hostPort.length() == 1) {
            return false;
        }
        final String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        return host.equalsIgnoreCase("localhost") || host.contains(".") || host.contains("-");
    }

    private static String defaultSchemeForHostPath(final String value) {
        final int slash = value.indexOf('/');
        final String hostPort = slash >= 0 ? value.substring(0, slash) : value;
        final String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        return host.equalsIgnoreCase("localhost") || host.startsWith("127.") ? "http" : "https";
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

