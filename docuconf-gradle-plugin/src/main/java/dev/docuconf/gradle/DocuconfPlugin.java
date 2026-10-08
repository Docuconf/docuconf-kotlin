package dev.docuconf.gradle;

import java.util.ArrayList;
import java.util.List;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * {@code id("dev.docuconf")}: registers {@code docuconfExport}, which writes the contract from the
 * app's classpath, and {@code docuconfCheck}, which fails with a diff when the committed contract is
 * out of date. {@code check} depends on {@code docuconfCheck}. {@code docuconfKDoc} indexes the KDoc of the
 * config classes into a resource, so a parameter's KDoc gives its description and details (SPEC section 14.7). Both run docuconf-hoplite's
 * {@code dev.docuconf.hoplite.Export} command, so the app must depend on docuconf-hoplite.
 */
public class DocuconfPlugin implements Plugin<Project> {
    public static final String GROUP = "docuconf";

    @Override
    public void apply(Project project) {
        DocuconfExtension ext = project.getExtensions().create("docuconf", DocuconfExtension.class);
        ext.getContract().convention(project.getLayout().getProjectDirectory().file("contract.cue"));
        project.getPluginManager().withPlugin("java", plugin -> {
            SourceSet main = project.getExtensions().getByType(SourceSetContainer.class).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            // KDoc of config classes, as a resource docuconf-hoplite reads for descriptions and details.
            TaskProvider<DocuconfKDocTask> kdoc = project.getTasks().register("docuconfKDoc", DocuconfKDocTask.class, task -> {
                task.setGroup(GROUP);
                task.setDescription("Indexes the KDoc of config class parameters for docuconf descriptions and details.");
                // Kotlin's source directories (the Kotlin plugin's "kotlin" extension of the source set) and Java's,
                // which may hold .kt files too. Not allSource: it includes the resources this task writes.
                task.getSources().from(project.provider(() -> {
                    Object kotlin = main.getExtensions().findByName("kotlin");
                    return kotlin == null ? List.of(main.getJava()) : List.of(kotlin, main.getJava());
                }));
                task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/docuconf/kdoc"));
            });
            main.getResources().srcDir(kdoc.flatMap(DocuconfKDocTask::getOutputDir));
            register(project, "docuconfExport", "Exports the docuconf contract of the config class.", ext, main, false);
            TaskProvider<JavaExec> check = register(project, "docuconfCheck", "Fails when the committed docuconf contract differs from a fresh export.", ext, main, true);
            project.getTasks().named("check").configure(t -> t.dependsOn(check));
        });
    }

    private static TaskProvider<JavaExec> register(Project project, String name, String description, DocuconfExtension ext, SourceSet main, boolean check) {
        return project.getTasks().register(name, JavaExec.class, task -> {
            task.setGroup(GROUP);
            task.setDescription(description);
            task.setClasspath(main.getRuntimeClasspath());
            task.getMainClass().set("dev.docuconf.hoplite.Export");
            task.getArgumentProviders().add(() -> arguments(ext, check));
        });
    }

    /** The {@code Export} command line for [ext]. */
    static List<String> arguments(DocuconfExtension ext, boolean check) {
        if (!ext.getConfigClass().isPresent()) {
            throw new GradleException("docuconf: set docuconf { configClass.set(\"com.example.AppConfig\") }");
        }
        List<String> args = new ArrayList<>(List.of("--class", ext.getConfigClass().get()));
        if (ext.getService().isPresent()) args.addAll(List.of("--service", ext.getService().get()));
        if (ext.getAppVersion().isPresent()) args.addAll(List.of("--app-version", ext.getAppVersion().get()));
        args.addAll(List.of("--out", ext.getContract().get().getAsFile().getPath()));
        if (ext.getMarkdown().isPresent()) args.addAll(List.of("--markdown", ext.getMarkdown().get().getAsFile().getPath()));
        if (check) args.add("--check");
        return args;
    }
}
