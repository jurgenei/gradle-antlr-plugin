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

    public GrammarCatalogEntry(
            final String name,
            final String runtimeGrammar,
            final String parser,
            final String lexer) {
        this.name = name;
        this.runtimeGrammar = runtimeGrammar;
        this.parser = parser;
        this.lexer = lexer;
    }

    public String getName() {
        return name;
    }

    public String getRuntimeGrammar() {
        return runtimeGrammar;
    }

    public String getParser() {
        return parser;
    }

    public String getLexer() {
        return lexer;
    }

    public String resolveRuntimeGrammar() {
        return runtimeGrammar == null || runtimeGrammar.isBlank() ? name : runtimeGrammar;
    }

    public Path resolveParserPath(final Path baseDirectory) {
        return CatalogPathResolver.resolveToPath(parser, baseDirectory);
    }

    public Path resolveLexerPath(final Path baseDirectory) {
        return CatalogPathResolver.resolveToPath(lexer, baseDirectory);
    }

    public URI resolveParserUri(final Path baseDirectory) {
        return CatalogPathResolver.resolveToUri(parser, baseDirectory);
    }

    public URI resolveLexerUri(final Path baseDirectory) {
        return CatalogPathResolver.resolveToUri(lexer, baseDirectory);
    }
}

