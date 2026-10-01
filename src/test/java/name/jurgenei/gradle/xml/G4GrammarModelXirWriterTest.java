package name.jurgenei.gradle.xml;

import name.jurgenei.ast.core.model.ChoiceNode;
import name.jurgenei.ast.core.model.GrammarModel;
import name.jurgenei.ast.core.model.GrammarNode;
import name.jurgenei.ast.core.model.GrammarRule;
import name.jurgenei.ast.core.model.LabelNode;
import name.jurgenei.ast.core.model.LiteralNode;
import name.jurgenei.ast.core.model.OptionalNode;
import name.jurgenei.ast.core.model.Repeat1Node;
import name.jurgenei.ast.core.model.RepeatNode;
import name.jurgenei.ast.core.model.RuleRefNode;
import name.jurgenei.ast.core.model.SequenceNode;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class G4GrammarModelXirWriterTest {

    @Test
    public void writesAllSupportedNodeTypesWithEscapedLiterals() {
        final GrammarNode body = new ChoiceNode(List.of(
                new SequenceNode(List.of(
                        new LabelNode("target", new RuleRefNode("identifier")),
                        new OptionalNode(new LiteralNode("\"quoted\\\\path\"")),
                        new RepeatNode(new RuleRefNode("item")),
                        new Repeat1Node(new RuleRefNode("term")))),
                new LiteralNode("fallback")));

        final GrammarModel model = new GrammarModel(List.of(new GrammarRule("root", body)));

        final String xir = new G4GrammarModelXirWriter().write(model);

        Assert.assertTrue(xir.contains("(rule root "));
        Assert.assertTrue(xir.contains("(choice "));
        Assert.assertTrue(xir.contains("(sequence "));
        Assert.assertTrue(xir.contains("(label target (ruleRef identifier))"));
        Assert.assertTrue(xir.contains("(optional (literal "));
        Assert.assertTrue(xir.contains("(repeat (ruleRef item))"));
        Assert.assertTrue(xir.contains("(repeat1 (ruleRef term))"));
        Assert.assertTrue(xir.contains("(literal \"fallback\")"));
        Assert.assertTrue("Expected escaped quote in literal output", xir.contains("\\\"quoted"));
        Assert.assertTrue("Expected escaped backslash in literal output", xir.contains("\\\\path"));
    }

    @Test
    public void failsWhenRuleBodyIsNull() {
        final GrammarModel model = new GrammarModel(List.of(new GrammarRule("broken", null)));
        Assert.assertThrows(NullPointerException.class, () -> new G4GrammarModelXirWriter().write(model));
    }
}
