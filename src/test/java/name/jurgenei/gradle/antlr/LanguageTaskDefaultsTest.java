package name.jurgenei.gradle.antlr;

import org.gradle.testfixtures.ProjectBuilder;
import org.junit.Assert;
import org.junit.Test;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public class LanguageTaskDefaultsTest {

    @Test
    public void ofFiveArgsUsesXmlAndCompactDefaults() {
        final LanguageTaskDefaults defaults = LanguageTaskDefaults.of(
                "antlr4",
                "example.Parser",
                "example.Lexer",
                "script",
                List.of("**/*.sql"));

        Assert.assertEquals("antlr4", defaults.grammar());
        Assert.assertEquals("example.Parser", defaults.parserClassName());
        Assert.assertEquals("example.Lexer", defaults.lexerClassName());
        Assert.assertEquals("script", defaults.startRule());
        Assert.assertEquals(List.of("**/*.sql"), defaults.includes());
        Assert.assertEquals(".xml", defaults.targetExtension());
        Assert.assertEquals("compact", defaults.sexprFormat());
    }

    @Test
    public void ofSevenArgsKeepsExplicitValues() {
        final LanguageTaskDefaults defaults = LanguageTaskDefaults.of(
                "fedsql",
                "f.Parser",
                "f.Lexer",
                "root",
                List.of("**/*.fed"),
                ".sexpr",
                "beautified");

        Assert.assertEquals("fedsql", defaults.grammar());
        Assert.assertEquals("f.Parser", defaults.parserClassName());
        Assert.assertEquals("f.Lexer", defaults.lexerClassName());
        Assert.assertEquals("root", defaults.startRule());
        Assert.assertEquals(List.of("**/*.fed"), defaults.includes());
        Assert.assertEquals(".sexpr", defaults.targetExtension());
        Assert.assertEquals("beautified", defaults.sexprFormat());
    }

    @Test
    public void constructorRejectsNullForEachField() {
        assertNullRejected("grammar cannot be null", null, "p", "l", "r", List.of("**/*.sql"), ".xml", "compact");
        assertNullRejected("parserClassName cannot be null", "g", null, "l", "r", List.of("**/*.sql"), ".xml", "compact");
        assertNullRejected("lexerClassName cannot be null", "g", "p", null, "r", List.of("**/*.sql"), ".xml", "compact");
        assertNullRejected("startRule cannot be null", "g", "p", "l", null, List.of("**/*.sql"), ".xml", "compact");
        assertNullRejected("includes cannot be null", "g", "p", "l", "r", null, ".xml", "compact");
        assertNullRejected("targetExtension cannot be null", "g", "p", "l", "r", List.of("**/*.sql"), null, "compact");
        assertNullRejected("sexprFormat cannot be null", "g", "p", "l", "r", List.of("**/*.sql"), ".xml", null);
    }

    @Test
    public void applyToSetsTaskConventionsAndCopiesIncludes() {
        final var project = ProjectBuilder.builder().build();
        final XmlAstGradleTask task = project.getTasks().create("xmlAst", TestXmlAstTask.class);

        final List<String> includes = new ArrayList<>();
        includes.add("**/*.sql");

        final LanguageTaskDefaults defaults = LanguageTaskDefaults.of(
                "oracle",
                "o.Parser",
                "o.Lexer",
                "script",
                includes,
                ".sexpr",
                "beautified");

        defaults.applyTo(task);
        includes.add("**/*.ignored");

        Assert.assertEquals("oracle", task.getGrammar().get());
        Assert.assertEquals("o.Parser", task.getParserClassName().get());
        Assert.assertEquals("o.Lexer", task.getLexerClassName().get());
        Assert.assertEquals("script", task.getStartRule().get());
        Assert.assertEquals(List.of("**/*.sql"), task.getIncludes().get());
        Assert.assertEquals(".sexpr", task.getTargetExtension().get());
        Assert.assertEquals("beautified", task.getSexprFormat().get());
    }

    private static void assertNullRejected(
            final String expectedMessage,
            final String grammar,
            final String parser,
            final String lexer,
            final String startRule,
            final List<String> includes,
            final String targetExtension,
            final String sexprFormat) {
        final NullPointerException ex = Assert.assertThrows(
                NullPointerException.class,
                () -> new LanguageTaskDefaults(grammar, parser, lexer, startRule, includes, targetExtension, sexprFormat));
        Assert.assertEquals(expectedMessage, ex.getMessage());
    }

    public abstract static class TestXmlAstTask extends XmlAstGradleTask {
        @Inject
        public TestXmlAstTask(final org.gradle.api.model.ObjectFactory objects) {
            super(objects);
        }
    }
}

