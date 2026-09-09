package name.jurgenei.gradle.antlr;

import java.util.List;
import java.util.Objects;

/**
 * Shared conventions for language-specific XmlAst task variants.
 *
 * <p>Language plugin modules use this type to reduce constructor boilerplate and keep
 * task defaults consistent when adding new language integrations.</p>
 */
public record LanguageTaskDefaults(
        String grammar,
        String parserClassName,
        String lexerClassName,
        String startRule,
        List<String> includes,
        String targetExtension,
        String sexprFormat) {

    /**
     * Creates language defaults with XML output and compact S-expression rendering defaults.
     */
    public static LanguageTaskDefaults of(
            final String grammar,
            final String parserClassName,
            final String lexerClassName,
            final String startRule,
            final List<String> includes) {
        return new LanguageTaskDefaults(
                grammar,
                parserClassName,
                lexerClassName,
                startRule,
                includes,
                ".xml",
                "compact");
    }

    /**
     * Creates language defaults with explicit output extension and S-expression format.
     */
    public static LanguageTaskDefaults of(
            final String grammar,
            final String parserClassName,
            final String lexerClassName,
            final String startRule,
            final List<String> includes,
            final String targetExtension,
            final String sexprFormat) {
        return new LanguageTaskDefaults(
                grammar,
                parserClassName,
                lexerClassName,
                startRule,
                includes,
                targetExtension,
                sexprFormat);
    }

    public LanguageTaskDefaults {
        Objects.requireNonNull(grammar, "grammar cannot be null");
        Objects.requireNonNull(parserClassName, "parserClassName cannot be null");
        Objects.requireNonNull(lexerClassName, "lexerClassName cannot be null");
        Objects.requireNonNull(startRule, "startRule cannot be null");
        Objects.requireNonNull(includes, "includes cannot be null");
        Objects.requireNonNull(targetExtension, "targetExtension cannot be null");
        Objects.requireNonNull(sexprFormat, "sexprFormat cannot be null");
    }

    /**
     * Applies defaults to a language-specific XmlAst task.
     */
    public void applyTo(final XmlAstGradleTask task) {
        task.getGrammar().convention(grammar);
        task.getParserClassName().convention(parserClassName);
        task.getLexerClassName().convention(lexerClassName);
        task.getStartRule().convention(startRule);
        task.getIncludes().convention(List.copyOf(includes));
        task.getTargetExtension().convention(targetExtension);
        task.getSexprFormat().convention(sexprFormat);
    }
}

