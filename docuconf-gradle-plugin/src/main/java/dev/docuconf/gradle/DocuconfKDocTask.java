package dev.docuconf.gradle;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Indexes the KDoc of config class parameters into {@code META-INF/docuconf/kdoc.properties}, a
 * resource docuconf-hoplite reads at export and at boot: the first sentence of a parameter's KDoc is its
 * description, and the rest its details (SPEC section 14.7). KDoc is not in compiled classes, so this
 * reads the Kotlin sources.
 */
@CacheableTask
public abstract class DocuconfKDocTask extends DefaultTask {
    /** Where the resource goes, relative to {@link #getOutputDir()}. */
    public static final String RESOURCE = "META-INF/docuconf/kdoc.properties";

    /** @return the Kotlin sources to read */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** @return a resources directory that holds {@link #RESOURCE} */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    void index() throws IOException {
        Map<String, String> all = new TreeMap<>();
        for (File f : getSources().getFiles()) {
            if (f.getName().endsWith(".kt")) {
                all.putAll(KDocIndex.scan(Files.readString(f.toPath(), StandardCharsets.UTF_8)));
            }
        }
        Path out = getOutputDir().get().getAsFile().toPath().resolve(RESOURCE);
        Files.createDirectories(out.getParent());
        try (OutputStream os = Files.newOutputStream(out); Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            write(all, w);
        }
    }

    /** Writes [entries] as a properties file, sorted and without the date line, so builds are reproducible. */
    static void write(Map<String, String> entries, Writer w) {
        try {
            w.write("# KDoc of config class parameters, indexed by the dev.docuconf Gradle plugin.\n");
            for (Map.Entry<String, String> e : entries.entrySet()) {
                w.write(escape(e.getKey(), true) + "=" + escape(e.getValue(), false) + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String escape(String s, boolean key) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '=', ':', '#', '!' -> sb.append('\\').append(c);
                case ' ' -> sb.append(key || i == 0 ? "\\ " : " ");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
