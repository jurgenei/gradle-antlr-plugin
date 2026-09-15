package name.jurgenei.gradle.xml;

import name.jurgenei.ast.core.AstClassesPipeline;
import name.jurgenei.ast.core.AstSexprWriter;
import name.jurgenei.ast.core.model.AstModel;
import name.jurgenei.ast.core.model.GrammarModel;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts ANTLRv4 grammar files into GrammarModel and AST-Classes S-expression files.
 */
@DisableCachingByDefault(because = "Parses grammar files and writes derived model artifacts")
public abstract class G4toClassTask extends SourceTask {

    @Optional
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getInputFile();

    @Optional
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Optional
    @OutputFile
    public abstract RegularFileProperty getModelOutputFile();

    @Input
    public abstract Property<String> getClassOutputExtension();

    @Input
    public abstract Property<String> getModelOutputExtension();

    @Input
    public abstract Property<String> getLexerClassName();

    @Input
    public abstract Property<String> getParserClassName();

    @Input
    public abstract Property<String> getStartRule();

    @Input
    public abstract Property<Boolean> getFailOnError();

    public G4toClassTask() {
        getClassOutputExtension().convention(".classes.sexp");
        getModelOutputExtension().convention(".model.sexp");
        getLexerClassName().convention("name.jurgenei.parsers.ANTLRv4Lexer");
        getParserClassName().convention("name.jurgenei.parsers.ANTLRv4Parser");
        getStartRule().convention("grammarSpec");
        getFailOnError().convention(true);
    }

    public void fileset(final Object baseDir, final Action<? super ConfigurableFileTree> configureAction) {
        final ConfigurableFileTree tree = getProject().fileTree(baseDir);
        configureAction.execute(tree);
        source(tree);
    }

    public void input(final Object path) {
        final File file = getProject().file(path);
        getInputFile().set(file);
        source(file);
    }

    public void output(final Object path) {
        getOutputFile().set(getProject().file(path));
    }

    public void modelOutput(final Object path) {
        getModelOutputFile().set(getProject().file(path));
    }

    @TaskAction
    public void convertAll() {
        final boolean hasExplicitInput = getInputFile().isPresent();
        final boolean hasExplicitOutput = getOutputFile().isPresent();
        if (hasExplicitInput != hasExplicitOutput) {
            throw new GradleException("Both inputFile and outputFile must be set together for single-file mode");
        }

        if (hasExplicitInput) {
            convertExplicit();
            return;
        }

        final List<File> inputFiles = new ArrayList<>(getSource().getFiles());
        Collections.sort(inputFiles);
        if (inputFiles.isEmpty()) {
            getLogger().lifecycle("{}: no input files matched", getName());
            return;
        }

        if (!getOutputDir().isPresent()) {
            throw new GradleException("outputDir is required when outputFile is not set");
        }

        final File outputRoot = getOutputDir().get().getAsFile();
        mkdirs(outputRoot);

        final Path sourceRoot = sharedSourceRoot(inputFiles);
        for (File inputFile : inputFiles) {
            try {
                final Path relative = sourceRoot.relativize(inputFile.toPath());
                final String relativeNoExt = stripExtension(relative.toString());
                final File classesOut = new File(outputRoot, relativeNoExt + getClassOutputExtension().get());
                final File modelOut = new File(outputRoot, relativeNoExt + getModelOutputExtension().get());
                convertOne(inputFile, classesOut, modelOut);
            } catch (Exception ex) {
                handleFailure(inputFile, ex);
            }
        }
    }

    private void convertExplicit() {
        final File input = getInputFile().get().getAsFile();
        final File classesOutput = getOutputFile().get().getAsFile();
        final File modelOutput = getModelOutputFile().isPresent()
                ? getModelOutputFile().get().getAsFile()
                : new File(replaceExtension(classesOutput.getPath(), getModelOutputExtension().get()));
        try {
            convertOne(input, classesOutput, modelOutput);
        } catch (Exception ex) {
            handleFailure(input, ex);
        }
    }

    private void convertOne(final File inputFile, final File classesOut, final File modelOut) throws IOException {
        if (!inputFile.isFile()) {
            throw new GradleException("Input file does not exist: " + inputFile);
        }

        final G4GrammarModelMapper modelMapper = new G4GrammarModelMapper();
        final GrammarModel grammarModel = modelMapper.map(
                inputFile,
                getLexerClassName().get(),
                getParserClassName().get(),
                getStartRule().get(),
                getClass().getClassLoader());

        final AstModel astModel = new AstClassesPipeline().deriveFromGrammarModel(grammarModel);
        final String classesSexpr = new AstSexprWriter().write(astModel);
        final String grammarModelSexpr = new G4GrammarModelSexprWriter().write(grammarModel);

        if (classesOut.getParentFile() != null) {
            mkdirs(classesOut.getParentFile());
        }
        if (modelOut.getParentFile() != null) {
            mkdirs(modelOut.getParentFile());
        }

        Files.writeString(classesOut.toPath(), classesSexpr, StandardCharsets.UTF_8);
        Files.writeString(modelOut.toPath(), grammarModelSexpr, StandardCharsets.UTF_8);

        getLogger().lifecycle("[SUCCESS] {} -> {}, {}", inputFile, classesOut, modelOut);
    }

    private void handleFailure(final File inputFile, final Exception ex) {
        getLogger().error("Failed to derive grammar model for {}", inputFile, ex);
        if (getFailOnError().get()) {
            throw new GradleException("Failed to derive model for: " + inputFile, ex);
        }
    }

    private void mkdirs(final File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new GradleException("Could not create directory: " + dir);
        }
    }

    private Path sharedSourceRoot(final List<File> files) {
        Path current = files.getFirst().toPath().getParent();
        for (File file : files) {
            current = commonPrefix(current, file.toPath().getParent());
        }
        return current == null ? getProject().getProjectDir().toPath() : current;
    }

    private Path commonPrefix(final Path a, final Path b) {
        if (a == null || b == null) {
            return null;
        }
        final int max = Math.min(a.getNameCount(), b.getNameCount());
        Path result = a.getRoot();
        for (int i = 0; i < max; i++) {
            if (!a.getName(i).equals(b.getName(i))) {
                break;
            }
            result = result == null ? a.getName(i) : result.resolve(a.getName(i));
        }
        return result;
    }

    private String stripExtension(final String path) {
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
        final int dot = path.lastIndexOf('.');
        if (dot > slash) {
            return path.substring(0, dot);
        }
        return path;
    }

    private String replaceExtension(final String path, final String extension) {
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf(File.separatorChar));
        final int dot = path.lastIndexOf('.');
        if (dot > slash) {
            return path.substring(0, dot) + extension;
        }
        return path + extension;
    }
}

