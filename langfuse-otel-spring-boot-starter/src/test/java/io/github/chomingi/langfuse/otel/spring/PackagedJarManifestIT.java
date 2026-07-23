package io.github.chomingi.langfuse.otel.spring;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class PackagedJarManifestIT {

    @Test
    void packagedJarDeclaresItsImplementationVersion() throws IOException {
        String packagedJar = System.getProperty("packagedJar");
        String expectedVersion = System.getProperty("expectedImplementationVersion");

        try (JarFile jarFile = new JarFile(packagedJar)) {
            assertThat(jarFile.getManifest().getMainAttributes()
                    .getValue(Attributes.Name.IMPLEMENTATION_VERSION))
                    .isEqualTo(expectedVersion);
        }
    }
}
