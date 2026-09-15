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
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.RuleNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.gradle.api.GradleException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps ANTLRv4 grammar source into GrammarModel. Prefer parse-tree extraction, fallback to text parser.
 */
public final class G4GrammarModelMapper {

    private static final Pattern PARSER_RULE_PATTERN = Pattern.compile("(?ms)^\\s*([a-z][A-Za-z0-9_]*)\\s*:\\s*(.*?)\\s*;");

    public GrammarModel map(
            final File grammarFile,
            final String lexerClassName,
            final String parserClassName,
            final String startRule,
            final ClassLoader classLoader) {
        try {
            return mapViaParseTree(grammarFile, lexerClassName, parserClassName, startRule, classLoader);
        } catch (Exception ex) {
            return mapViaText(grammarFile);
        }
    }

    private GrammarModel mapViaParseTree(
            final File grammarFile,
            final String lexerClassName,
            final String parserClassName,
            final String startRule,
            final ClassLoader classLoader) throws Exception {
        final CharStream charStream = CharStreams.fromPath(grammarFile.toPath());
        final Class<?> lexerRaw = Class.forName(lexerClassName, true, classLoader);
        final Class<?> parserRaw = Class.forName(parserClassName, true, classLoader);

        if (!Lexer.class.isAssignableFrom(lexerRaw)) {
            throw new GradleException("Configured lexer is not an ANTLR lexer: " + lexerClassName);
        }
        if (!Parser.class.isAssignableFrom(parserRaw)) {
            throw new GradleException("Configured parser is not an ANTLR parser: " + parserClassName);
        }

        @SuppressWarnings("unchecked") final Class<? extends Lexer> lexerClass = (Class<? extends Lexer>) lexerRaw;
        @SuppressWarnings("unchecked") final Class<? extends Parser> parserClass = (Class<? extends Parser>) parserRaw;

        final Constructor<? extends Lexer> lexerCtor = lexerClass.getConstructor(CharStream.class);
        final Lexer lexer = lexerCtor.newInstance(charStream);
        final CommonTokenStream tokenStream = new CommonTokenStream(lexer);

        final Constructor<? extends Parser> parserCtor = parserClass.getConstructor(TokenStream.class);
        final Parser parser = parserCtor.newInstance(tokenStream);
        final Method startRuleMethod = parserClass.getMethod(startRule);
        final ParseTree root = (ParseTree) startRuleMethod.invoke(parser);

        final List<GrammarRule> rules = new ArrayList<>();
        collectParserRules(root, parser, tokenStream, rules);
        if (!rules.isEmpty()) {
            return new GrammarModel(rules);
        }

        return mapViaText(grammarFile);
    }

    private void collectParserRules(
            final ParseTree node,
            final Parser parser,
            final CommonTokenStream tokens,
            final List<GrammarRule> outRules) {
        if (node instanceof RuleNode ruleNode && "parserRuleSpec".equals(ruleName(ruleNode, parser))) {
            final String parsedRuleName = firstTerminalWithSymbol(ruleNode, parser, "RULE_REF");
            final RuleNode ruleBlock = findFirstRuleNode(ruleNode, parser, "ruleBlock");
            if (parsedRuleName != null && ruleBlock != null) {
                final String bodyText = tokens.getText(intervalOf(ruleBlock));
                outRules.add(new GrammarRule(parsedRuleName, parseRuleBody(bodyText)));
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectParserRules(node.getChild(i), parser, tokens, outRules);
        }
    }

    private Interval intervalOf(final RuleNode node) {
        final org.antlr.v4.runtime.RuleContext context = node.getRuleContext();
        if (context instanceof org.antlr.v4.runtime.ParserRuleContext parserRuleContext
                && parserRuleContext.getStart() != null
                && parserRuleContext.getStop() != null) {
            return Interval.of(parserRuleContext.getStart().getTokenIndex(), parserRuleContext.getStop().getTokenIndex());
        }
        return Interval.INVALID;
    }

    private String firstTerminalWithSymbol(final ParseTree node, final Parser parser, final String symbolicName) {
        if (node instanceof TerminalNode terminal) {
            final Token token = terminal.getSymbol();
            final String symbol = parser.getVocabulary().getSymbolicName(token.getType());
            if (symbolicName.equals(symbol)) {
                return terminal.getText();
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            final String candidate = firstTerminalWithSymbol(node.getChild(i), parser, symbolicName);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private RuleNode findFirstRuleNode(final ParseTree node, final Parser parser, final String expectedRuleName) {
        if (node instanceof RuleNode ruleNode && expectedRuleName.equals(ruleName(ruleNode, parser))) {
            return ruleNode;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            final RuleNode candidate = findFirstRuleNode(node.getChild(i), parser, expectedRuleName);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private String ruleName(final RuleNode node, final Parser parser) {
        final int idx = node.getRuleContext().getRuleIndex();
        final String[] names = parser.getRuleNames();
        if (idx < 0 || idx >= names.length) {
            return null;
        }
        return names[idx];
    }

    private GrammarModel mapViaText(final File grammarFile) {
        final String content;
        try {
            content = Files.readString(grammarFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GradleException("Failed to read grammar file: " + grammarFile, ex);
        }

        final String sanitized = stripComments(content);
        final Matcher matcher = PARSER_RULE_PATTERN.matcher(sanitized);
        final List<GrammarRule> rules = new ArrayList<>();
        while (matcher.find()) {
            final String ruleName = matcher.group(1);
            final String body = matcher.group(2);
            rules.add(new GrammarRule(ruleName, parseRuleBody(body)));
        }
        return new GrammarModel(rules);
    }

    private String stripComments(final String content) {
        final String withoutBlock = content.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("(?m)//.*$", " ");
    }

    private GrammarNode parseRuleBody(final String bodyText) {
        final List<String> alternatives = splitTopLevel(bodyText, '|');
        final List<GrammarNode> altNodes = new ArrayList<>();
        for (String alt : alternatives) {
            final String trimmed = alt.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            altNodes.add(parseSequence(trimmed));
        }
        if (altNodes.isEmpty()) {
            return new SequenceNode(List.of());
        }
        if (altNodes.size() == 1) {
            return altNodes.getFirst();
        }
        return new ChoiceNode(altNodes);
    }

    private GrammarNode parseSequence(final String text) {
        final List<String> terms = splitTopLevelWhitespace(text);
        final List<GrammarNode> nodes = new ArrayList<>();
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            final GrammarNode node = parseTerm(term.trim());
            if (node != null) {
                nodes.add(node);
            }
        }
        if (nodes.isEmpty()) {
            return new SequenceNode(List.of());
        }
        if (nodes.size() == 1) {
            return nodes.getFirst();
        }
        return new SequenceNode(nodes);
    }

    private GrammarNode parseTerm(final String term) {
        final Matcher labelMatcher = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=(.+)$").matcher(term);
        if (labelMatcher.matches()) {
            final String label = labelMatcher.group(1);
            final GrammarNode target = parseFactor(labelMatcher.group(2).trim());
            return target == null ? null : new LabelNode(label, target);
        }
        return parseFactor(term);
    }

    private GrammarNode parseFactor(final String raw) {
        if (raw.isEmpty()) {
            return null;
        }

        final char last = raw.charAt(raw.length() - 1);
        final boolean hasQuantifier = last == '?' || last == '*' || last == '+';
        final String base = hasQuantifier ? raw.substring(0, raw.length() - 1).trim() : raw.trim();

        GrammarNode baseNode;
        if (base.startsWith("(") && base.endsWith(")") && isBalancedParens(base)) {
            final String inside = base.substring(1, base.length() - 1);
            baseNode = parseRuleBody(inside);
        } else if (base.startsWith("'")) {
            baseNode = new LiteralNode(base);
        } else if (base.startsWith(".") || base.startsWith("~")) {
            baseNode = new LiteralNode(base);
        } else if (base.isEmpty()) {
            baseNode = null;
        } else {
            final char first = base.charAt(0);
            if (Character.isUpperCase(first)) {
                baseNode = new LiteralNode(base);
            } else {
                final String normalized = base.replaceAll("[<>].*", "");
                baseNode = new RuleRefNode(normalized);
            }
        }

        if (!hasQuantifier || baseNode == null) {
            return baseNode;
        }
        return switch (last) {
            case '?' -> new OptionalNode(baseNode);
            case '*' -> new RepeatNode(baseNode);
            case '+' -> new Repeat1Node(baseNode);
            default -> baseNode;
        };
    }

    private List<String> splitTopLevelWhitespace(final String text) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        char stringDelim = 0;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                current.append(c);
                if (c == stringDelim && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                inString = true;
                stringDelim = c;
                current.append(c);
                continue;
            }

            if (c == '(') {
                depth++;
                current.append(c);
                continue;
            }
            if (c == ')' && depth > 0) {
                depth--;
                current.append(c);
                continue;
            }

            if (Character.isWhitespace(c) && depth == 0) {
                if (!current.isEmpty()) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    private List<String> splitTopLevel(final String text, final char delimiter) {
        final List<String> parts = new ArrayList<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        char stringDelim = 0;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                current.append(c);
                if (c == stringDelim && (i == 0 || text.charAt(i - 1) != '\\')) {
                    inString = false;
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                inString = true;
                stringDelim = c;
                current.append(c);
                continue;
            }

            if (c == '(') {
                depth++;
                current.append(c);
                continue;
            }
            if (c == ')' && depth > 0) {
                depth--;
                current.append(c);
                continue;
            }

            if (c == delimiter && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        parts.add(current.toString());
        return parts;
    }

    private boolean isBalancedParens(final String text) {
        int depth = 0;
        boolean inString = false;
        char stringDelim = 0;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (inString) {
                if (c == stringDelim && text.charAt(i - 1) != '\\') {
                    inString = false;
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                inString = true;
                stringDelim = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return false;
                }
            }
        }
        return depth == 0;
    }
}

