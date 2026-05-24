package name.jurgenei.gradle.antlr.catalog;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class GrammarCatalogLoaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsCatalogAndResolvesRuntimeGrammar() throws Exception {
        final File parser = temporaryFolder.newFile("PlSqlParser.g4");
        final File lexer = temporaryFolder.newFile("PlSqlLexer.g4");
        final File catalog = temporaryFolder.newFile("catalog.xml");

        Files.writeString(catalog.toPath(), """
                <catalog>
                  <grammar name="plsql" runtimeGrammar="oracle" parser="%s" lexer="%s"/>
                </catalog>
                """.formatted(parser.getName(), lexer.getName()), StandardCharsets.UTF_8);

        final GrammarCatalogLoader loader = new GrammarCatalogLoader();
        final GrammarCatalogEntry entry = loader.load(catalog).require("plsql");

        Assert.assertEquals("oracle", entry.resolveRuntimeGrammar());
        Assert.assertEquals(parser.toPath().toAbsolutePath().normalize(), entry.resolveParserPath(catalog.toPath().getParent()));
        Assert.assertEquals(lexer.toPath().toAbsolutePath().normalize(), entry.resolveLexerPath(catalog.toPath().getParent()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateGrammarNames() throws Exception {
        final File catalog = temporaryFolder.newFile("duplicate.xml");
        Files.writeString(catalog.toPath(), """
                <catalog>
                  <grammar name="plsql" parser="a.g4" lexer="b.g4"/>
                  <grammar name="plsql" parser="c.g4" lexer="d.g4"/>
                </catalog>
                """, StandardCharsets.UTF_8);

        new GrammarCatalogLoader().load(catalog);
    }
}

