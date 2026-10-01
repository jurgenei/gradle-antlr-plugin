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

/**
 * Writes GrammarModel into compact S-expression format.
 */
public final class G4GrammarModelXirWriter {

    public String write(final GrammarModel grammarModel) {
        final StringBuilder out = new StringBuilder();
        for (GrammarRule rule : grammarModel.rules()) {
            out.append("(rule ").append(rule.name()).append(" ");
            writeNode(rule.body(), out);
            out.append(")\n");
        }
        return out.toString();
    }

    private void writeNode(final GrammarNode node, final StringBuilder out) {
        if (node instanceof SequenceNode(java.util.List<GrammarNode> elements)) {
            out.append("(sequence");
            for (GrammarNode child : elements) {
                out.append(' ');
                writeNode(child, out);
            }
            out.append(')');
            return;
        }
        if (node instanceof ChoiceNode(java.util.List<GrammarNode> alternatives)) {
            out.append("(choice");
            for (GrammarNode child : alternatives) {
                out.append(' ');
                writeNode(child, out);
            }
            out.append(')');
            return;
        }
        if (node instanceof OptionalNode(GrammarNode node4)) {
            out.append("(optional ");
            writeNode(node4, out);
            out.append(')');
            return;
        }
        if (node instanceof RepeatNode(GrammarNode node3)) {
            out.append("(repeat ");
            writeNode(node3, out);
            out.append(')');
            return;
        }
        if (node instanceof Repeat1Node(GrammarNode node2)) {
            out.append("(repeat1 ");
            writeNode(node2, out);
            out.append(')');
            return;
        }
        if (node instanceof LabelNode(String label1, GrammarNode node1)) {
            out.append("(label ").append(label1).append(' ');
            writeNode(node1, out);
            out.append(')');
            return;
        }
        if (node instanceof RuleRefNode(String ruleName)) {
            out.append("(ruleRef ").append(ruleName).append(')');
            return;
        }
        if (node instanceof LiteralNode(String text)) {
            out.append("(literal \"").append(escape(text)).append("\")");
            return;
        }
        throw new IllegalArgumentException("Unsupported node type: " + node.getClass().getName());
    }

    private String escape(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

