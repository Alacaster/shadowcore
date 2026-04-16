package dev.shadowcore.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.file.YamlConfiguration;

public final class YamlIO {

    private YamlIO() {}

    public static void saveAtomically(final File target, final YamlConfiguration yaml) {
        final File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try {
            target.getParentFile().mkdirs();
            yaml.save(tmp);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException exception) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException fallback) {
                throw new IllegalStateException("Failed to save YAML file " + target, fallback);
            }
        }
    }

    public static void ensureDirectory(final File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create directory: " + dir);
        }
    }
}
