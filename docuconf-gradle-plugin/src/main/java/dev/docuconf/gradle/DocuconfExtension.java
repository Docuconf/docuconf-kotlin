package dev.docuconf.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code docuconf { }} block. Only {@link #getConfigClass()} is required: the service name, env
 * prefix and base config files come from the class's {@code @DocuconfService} annotation.
 */
public abstract class DocuconfExtension {
    /** The root config class, such as {@code com.example.AppConfig}. */
    public abstract Property<String> getConfigClass();

    /** The service name, when the class has no {@code @DocuconfService(name = ...)}. */
    public abstract Property<String> getService();

    /** Recorded as {@code metadata.appVersion}. */
    public abstract Property<String> getAppVersion();

    /** The committed contract. Defaults to {@code contract.cue} in the project directory. */
    public abstract RegularFileProperty getContract();

    /** Optional Markdown docs of every input, written and checked with the contract. */
    public abstract RegularFileProperty getMarkdown();
}
