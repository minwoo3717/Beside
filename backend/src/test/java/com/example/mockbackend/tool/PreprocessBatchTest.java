package com.example.mockbackend.tool;

import com.example.mockbackend.preprocess.AnimalDetector;
import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import com.example.mockbackend.preprocess.Box;
import com.example.mockbackend.preprocess.ForegroundSegmenter;
import com.example.mockbackend.preprocess.YoloxCodec;
import com.example.mockbackend.service.preprocess.PreprocessProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The batch tool with fake models: the files per photo, report.csv and index.html. */
class PreprocessBatchTest {
    private static final Box ANIMAL = new Box(200, 150, 600, 450);

    @TempDir
    Path dir;

    /** A dog where the centre of the photo is red. */
    private static final AnimalDetector DETECTOR = image -> {
        Color centre = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
        return centre.getRed() > 150 && centre.getGreen() < 80
                ? List.of(new Detection(ANIMAL, YoloxCodec.COCO_DOG, 0.9f)) : List.of();
    };

    private static final ForegroundSegmenter RED_IS_FOREGROUND = crop -> {
        float[] alpha = new float[crop.getWidth() * crop.getHeight()];
        for (int y = 0; y < crop.getHeight(); y++) {
            for (int x = 0; x < crop.getWidth(); x++) {
                Color c = new Color(crop.getRGB(x, y));
                alpha[y * crop.getWidth() + x] = c.getRed() > 150 && c.getGreen() < 80 ? 1f : 0f;
            }
        }
        return alpha;
    };

    @Test
    void writesThePicturesTheTableAndThePageForEveryPhoto() throws Exception {
        Path in = Files.createDirectories(dir.resolve("in"));
        Path out = dir.resolve("out");
        ImageIO.write(scene(true), "png", in.resolve("b-dog.png").toFile());
        ImageIO.write(scene(false), "png", in.resolve("a-room.png").toFile());
        Files.write(in.resolve("c-broken.jpg"), "not a picture".getBytes(StandardCharsets.UTF_8));
        Files.writeString(in.resolve("notes.txt"), "not a photo: skipped");

        List<PreprocessBatch.Row> rows = PreprocessBatch.run(in, out, new PreprocessProperties(), DETECTOR,
                RED_IS_FOREGROUND, (image, minScore) -> List.of(), 512);

        assertThat(rows).extracting(PreprocessBatch.Row::file).containsExactly("a-room.png", "b-dog.png", "c-broken.jpg");
        assertThat(rows).extracting(PreprocessBatch.Row::decision)
                .containsExactly("NO_ANIMAL_DETECTED", "PASS", "UNREADABLE_IMAGE");

        Path dog = out.resolve(rows.get(1).folder());
        for (String file : List.of("original.jpg", "detect.jpg", "crop_pad.png", "cutout.png", "on_white.jpg",
                "model_in_before.png", "model_in_after.png")) {
            assertThat(dog.resolve(file)).as(file).isRegularFile();
        }
        BufferedImage cutout = ImageIO.read(dog.resolve("cutout.png").toFile());
        assertThat(cutout.getWidth()).isEqualTo(cutout.getHeight());
        BufferedImage modelIn = ImageIO.read(dog.resolve("model_in_after.png").toFile());
        assertThat(modelIn.getWidth()).isEqualTo(512);
        assertThat(rows.get(1).cutout()).isEqualTo("APPLIED");
        assertThat(rows.get(1).cropPad()).isEqualTo("APPLIED");
        assertThat(out.resolve(rows.get(0).folder()).resolve("detect.jpg")).isRegularFile();
        assertThat(rows.get(0).cutout()).isEqualTo("FALLBACK:NO_ANIMAL");

        List<String> csv = Files.readAllLines(out.resolve("report.csv"), StandardCharsets.UTF_8);
        assertThat(csv.get(0)).isEqualTo(BatchReport.CSV_HEADER);
        assertThat(csv).hasSize(4);
        assertThat(csv.get(2)).startsWith("b-dog.png,800,600,PASS,");

        String html = Files.readString(out.resolve("index.html"), StandardCharsets.UTF_8);
        assertThat(html).contains(rows.get(1).folder() + "/cutout.png", "report.csv", "NO_ANIMAL_DETECTED");
        assertThat(out.resolve("settings.txt")).isRegularFile();
    }

    @Test
    void settingsComeFromTheServersFilesAndTheCommandLine() {
        PreprocessProperties props = PreprocessBatch.settings(List.of("preprocess.check.min-sharpness=80",
                "preprocess.cutout.background=keep"), "C:/models");

        assertThat(props.checkRules().minSharpness()).isEqualTo(80.0);
        assertThat(props.getCutout().getBackground()).isEqualTo("keep");
        assertThat(props.checkRules().minImageSide()).as("from application.properties").isEqualTo(320);
        assertThat(props.getModelsDir()).isEqualTo("C:/models");
    }

    /** 800 x 600 grey; with an animal, a textured red rectangle (dark and light red cells) in ANIMAL. */
    private static BufferedImage scene(boolean animal) {
        BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 800; x++) {
                boolean in = animal && x >= ANIMAL.x1() && x < ANIMAL.x2() && y >= ANIMAL.y1() && y < ANIMAL.y2();
                Color c = !in ? new Color(150, 150, 150)
                        : ((x / 3 + y / 3) % 2 == 0 ? new Color(230, 40, 40) : new Color(160, 20, 20));
                image.setRGB(x, y, c.getRGB());
            }
        }
        return image;
    }
}
