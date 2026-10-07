package dev.docuconf.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class DocuconfPluginTest {
    @Test
    void registersExportAndCheck() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");
        project.getPluginManager().apply("dev.docuconf");
        DocuconfExtension ext = project.getExtensions().getByType(DocuconfExtension.class);
        ext.getConfigClass().set("com.example.AppConfig");

        JavaExec export = (JavaExec) project.getTasks().getByName("docuconfExport");
        JavaExec check = (JavaExec) project.getTasks().getByName("docuconfCheck");
        assertEquals("dev.docuconf.hoplite.Export", export.getMainClass().get());
        String contract = new File(project.getProjectDir(), "contract.cue").getPath();
        assertEquals(List.of("--class", "com.example.AppConfig", "--out", contract), DocuconfPlugin.arguments(ext, false));
        assertEquals(List.of("--class", "com.example.AppConfig", "--out", contract, "--check"), DocuconfPlugin.arguments(ext, true));
        assertTrue(project.getTasks().getByName("check").getTaskDependencies().getDependencies(null).contains(check));
    }
}
