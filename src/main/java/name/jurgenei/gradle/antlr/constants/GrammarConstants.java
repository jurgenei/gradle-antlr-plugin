package name.jurgenei.gradle.antlr.constants;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Centralized constants for grammar processing, execution models, and defaults.
 * Consolidates magic strings scattered throughout the codebase into a single source of truth.
 */
public final class GrammarConstants {

    // ════════════════════════════════════════════════════════════════════════════════
    // Execution Models
    // ════════════════════════════════════════════════════════════════════════════════

    /** Sequential execution model - processes files one at a time */
    public static final String EXECUTION_MODEL_SEQUENTIAL = "SEQUENTIAL";

    /** Platform threads execution model - uses fixed thread pool */
    public static final String EXECUTION_MODEL_PLATFORM_THREADS = "PLATFORM_THREADS";

    /** Virtual threads execution model - uses virtual threads per task */
    public static final String EXECUTION_MODEL_VIRTUAL_THREADS = "VIRTUAL_THREADS";

    // ════════════════════════════════════════════════════════════════════════════════
    // Default Values
    // ════════════════════════════════════════════════════════════════════════════════

    /** Default parallelism level */
    public static final int DEFAULT_PARALLELISM = 1;

    /** Default output file extension */
    public static final String DEFAULT_FILE_EXTENSION = ".xml";

    /** Default parser entry rule name */
    public static final String DEFAULT_START_RULE = "script";

    /** Default file include pattern for scanning */
    public static final String DEFAULT_INCLUDE_PATTERN = "**/*.sql";

    /** Default grammar name from catalog */
    public static final String DEFAULT_GRAMMAR = "oracle";

    // ════════════════════════════════════════════════════════════════════════════════
    // ANTLR Tool Arguments
    // ════════════════════════════════════════════════════════════════════════════════

    /** Default ANTLR tool command-line arguments */
    public static final List<String> ANTLR_TOOL_DEFAULT_ARGS = List.of(
        "-listener",
        "-no-visitor"
    );

    // ════════════════════════════════════════════════════════════════════════════════
    // Virtual Thread Naming
    // ════════════════════════════════════════════════════════════════════════════════

    /** Prefix for virtual thread names */
    public static final String VIRTUAL_THREAD_NAME_PREFIX = "xmlast-vt-";

    // ════════════════════════════════════════════════════════════════════════════════
    // Regular Expression Patterns
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Pattern for extracting grammar name from a grammar file.
     * Matches: [lexer|parser] grammar GrammarName;
     */
    public static final Pattern GRAMMAR_NAME_PATTERN = Pattern.compile(
        "(?:lexer|parser)?\\s*grammar\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;"
    );

    /**
     * Pattern for extracting package declaration from a Java source file.
     * Matches: package com.example.package;
     */
    public static final Pattern PACKAGE_DECLARATION_PATTERN = Pattern.compile(
        "^\\s*package\\s+([\\w.]+)\\s*;$"
    );

    // ════════════════════════════════════════════════════════════════════════════════
    // URI Scheme Prefixes
    // ════════════════════════════════════════════════════════════════════════════════

    /** HTTP URI scheme prefix */
    public static final String SCHEME_HTTP = "http://";

    /** HTTPS URI scheme prefix */
    public static final String SCHEME_HTTPS = "https://";

    /** File URI scheme prefix */
    public static final String SCHEME_FILE = "file:";

    /** Protocol-less URI prefix (becomes HTTPS) */
    public static final String SCHEME_PROTOCOL_LESS = "//";

    /** Grammar file extension */
    public static final String GRAMMAR_FILE_EXTENSION = ".g4";

    private GrammarConstants() {
        // Prevent instantiation
        throw new AssertionError("Cannot instantiate utility class");
    }
}

