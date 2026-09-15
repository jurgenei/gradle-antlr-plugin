package name.jurgenei.gradle.xml;

import name.jurgenei.ast.core.AstClassDeriver;
import name.jurgenei.ast.core.model.AstInheritance;
import name.jurgenei.ast.core.model.AstRelation;
import name.jurgenei.ast.core.model.Cardinality;
import name.jurgenei.ast.core.model.GrammarModel;
import name.jurgenei.ast.core.model.RelationKind;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class G4GrammarModelMapperTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mapsRuleSemanticsFor1B2A3A() throws Exception {
        final File grammarFile = temporaryFolder.newFile("mini.g4");
        Files.writeString(grammarFile.toPath(), sampleGrammar(), StandardCharsets.UTF_8);

        final GrammarModel model = new G4GrammarModelMapper().map(
                grammarFile,
                "name.jurgenei.parsers.ANTLRv4Lexer",
                "name.jurgenei.parsers.ANTLRv4Parser",
                "grammarSpec",
                getClass().getClassLoader());

        final var ast = new AstClassDeriver().derive(model);
        Assert.assertTrue(ast.relations().contains(new AstRelation("Assignment", "target", "Identifier", Cardinality.ONE, RelationKind.REL)));
        Assert.assertTrue(ast.relations().contains(new AstRelation("Assignment", "value", "Expression", Cardinality.ONE, RelationKind.REL)));
        Assert.assertTrue(ast.inheritances().contains(new AstInheritance("FunctionCall", "Expression")));
        Assert.assertFalse(ast.classes().stream().anyMatch(c -> c.name().equals("Terminator")));

        final String sexpr = new G4GrammarModelSexprWriter().write(model);
        Assert.assertTrue(sexpr.contains("(rule assignment"));
    }

    private static String sampleGrammar() {
        return """
                grammar Mini;

                assignment
                  : target=identifier '=' value=expression ';'
                  ;

                expression
                  : functionCall
                  | binaryExpression
                  | literalExpr
                  ;

                functionCall : identifier ;
                binaryExpression : identifier ;
                literalExpr : INT ;
                identifier : nameToken ;
                nameToken : ID ;
                terminator : ';' ;

                ID : [a-zA-Z_][a-zA-Z0-9_]* ;
                INT : [0-9]+ ;
                WS : [ \t\r\n]+ -> skip ;
                """;
    }
}

