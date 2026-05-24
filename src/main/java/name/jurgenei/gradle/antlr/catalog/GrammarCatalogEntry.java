package name.jurgenei.gradle.antlr.catalog;

import java.net.URI;
import java.nio.file.Path;

/**
 * Immutable grammar entry loaded from a catalog file.
 */
public final class GrammarCatalogEntry {

    private final String name;
    private final String runtimeGrammar;
    private final String parser;
    private final String lexer;
    private final String startRule;

    /**
     * Creates an immutable grammar catalog entry.
     *
     * @param name catalog grammar name.
     * @param runtimeGrammar optional runtime grammar key used by legacy converter mode.
     * @param parser parser coordinate (path or class name depending on task configuration).
     * @param lexer lexer coordinate (path or class name depending on task configuration).
     * @param startRule parser entry rule name.
     */
    public GrammarCatalogEntry(
            final String name,
            final String runtimeGrammar,
            final String parser,
            final String lexer,
            final String startRule) {
        this.name = name;
        this.runtimeGrammar = runtimeGrammar;
        this.parser = parser;
        this.lexer = lexer;
        this.startRule = startRule;
    }

    /**
     * @return catalog grammar name.
     */
    public String getName() {
        return name;
    }

    /**
     * @return optional runtime grammar key.
     */
    public String getRuntimeGrammar() {
        return runtimeGrammar;
    }

    /**
     * @return parser coordinate value.
     */
    public String getParser() {
        return parser;
    }

    /**
     * @return lexer coordinate value.
     */
    public String getLexer() {
        return lexer;
    }

    /**
     * @return parser entry rule name.
     */
    public String getStartRule() {
        return startRule;
    }

    /**
     * Resolves effective runtime grammar key.
     *
     * @return {@code runtimeGrammar} when provided, otherwise {@code name}.
     */
    public String resolveRuntimeGrammar() {
        return runtimeGrammar == null || runtimeGrammar.isBlank() ? name : runtimeGrammar;
    }

    /**
     * Resolves parser coordinate to a local path when applicable.
     *
     * @param baseDirectory base directory used for relative coordinates.
     * @return local parser path, or {@code null} for non-file URIs.
     */
    public Path resolveParserPath(final Path baseDirectory) {
        return CatalogPathResolver.resolveToPath(parser, baseDirectory);
    }

    /**
     * Resolves lexer coordinate to a local path when applicable.
     *
     * @param baseDirectory base directory used for relative coordinates.
     * @return local lexer path, or {@code null} for non-file URIs.
     */
    public Path resolveLexerPath(final Path baseDirectory) {
        return CatalogPathResolver.resolveToPath(lexer, baseDirectory);
    }

    /**
     * Resolves parser coordinate to a URI.
     *
     * @param baseDirectory base directory used for relative coordinates.
     * @return parser URI.
     */
    public URI resolveParserUri(final Path baseDirectory) {
        return CatalogPathResolver.resolveToUri(parser, baseDirectory);
    }

    /**
     * Resolves lexer coordinate to a URI.
     *
     * @param baseDirectory base directory used for relative coordinates.
     * @return lexer URI.
     */
    public URI resolveLexerUri(final Path baseDirectory) {
        return CatalogPathResolver.resolveToUri(lexer, baseDirectory);
    }
}

