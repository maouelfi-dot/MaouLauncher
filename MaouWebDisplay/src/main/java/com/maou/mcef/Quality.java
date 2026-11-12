package com.maou.mcef;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Pilihan kualitas layar web, dibaca dari config/maou_mcef.properties:
 *   quality=hemat | normal | tajam
 * hemat  = ±480x270, 5 fps  (HP RAM 2GB)
 * normal = ±800x450, 8 fps  (bawaan)
 * tajam  = ±960x540, 10 fps (HP yang lebih kuat)
 */
public final class Quality {
    private Quality() {}

    /** @return {maksimum piksel, fps} */
    public static int[] current() {
        String q = "normal";
        try {
            Path dir = FabricLoader.getInstance().getConfigDir();
            Path file = dir.resolve("maou_mcef.properties");
            Properties p = new Properties();
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) { p.load(in); }
            } else {
                p.setProperty("quality", "normal");
                Files.createDirectories(dir);
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out, "Maou MCEF: quality = hemat | normal | tajam");
                }
            }
            q = p.getProperty("quality", "normal").trim().toLowerCase();
        } catch (IOException | RuntimeException ignored) {
        }
        switch (q) {
            case "hemat": return new int[]{480 * 270, 5};
            case "tajam": return new int[]{960 * 540, 10};
            default: return new int[]{800 * 450, 8};
        }
    }
}
