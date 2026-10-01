package name.jurgenei.gradle.antlr.catalog;

import java.net.URI;
import java.nio.file.Path;

/**
 * Immutable grammar entry loaded from a catalog file.
 */
public record GrammarCatalogEntry(String name, String runtimeGrammar, String parser, String lexer, String startRule) {

    /**
     * Creates an immutable grammar catalog entry.
     *
     * @param name           catalog grammar name.
     * @param runtimeGrammar optional runtime grammar key used by legacy converter mode.
     * @param parser         parser coordinate (path or class name depending on task configuration).
     * @param lexer          lexer coordinate (path or class name depending on task configuration).
     * @param startRule      parser entry rule name.
     */
    public GrammarCatalogEntry {
    }

    /**
     * Returns the catalog grammar name.
     *
     * @return catalog grammar name.
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Returns the optional runtime grammar alias.
     *
     * @return optional runtime grammar key.
     */
    @Override
    public String runtimeGrammar() {
        return runtimeGrammar;
    }

    /**
     * Returns the parser coordinate from the catalog entry.
     *
     * @return parser coordinate value.
     */
    @Override
    public String parser() {
        return parser;
    }

    /**
     * Returns the lexer coordinate from the catalog entry.
     *
     * @return lexer coordinate value.
     */
    @Override
    public String lexer() {
        return lexer;
    }

    /**
     * Returns the parser start rule configured for this entry.
     *
     * @return parser entry rule name.
     */
    @Override
    public String startRule() {
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

