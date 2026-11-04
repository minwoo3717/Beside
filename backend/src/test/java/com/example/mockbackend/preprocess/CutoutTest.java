package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CutoutTest {
    private static final Color FUR = new Color(200, 40, 40);
    private static final Box ANIMAL = new Box(300, 200, 500, 350); // 200 x 150

    /** A grey photo with a red "animal" rectangle (and optionally a second red blob). */
    private static DecodedPhoto photo(int width, int height, Box animal, Box blob) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(150, 150, 150));
        g.fillRect(0, 0, width, height);
        g.setColor(FUR);
        g.fillRect((int) animal.x1(), (int) animal.y1(), (int) animal.width(), (int) animal.height());
        if (blob != null) {
            g.fillRect((int) blob.x1(), (int) blob.y1(), (int) blob.width(), (int) blob.height());
        }
        g.dispose();
        return Pictures.decoded(image);
    }

    private static AnimalDetector finds(Box box) {
        return image -> List.of(new Detection(box, YoloxCodec.COCO_DOG, 0.9f));
    }

    /** Alpha 1 where the pixel is the fur colour: a perfect segmenter for these pictures. */
    private static final ForegroundSegmenter RED_IS_FOREGROUND = crop -> {
        float[] alpha = new float[crop.getWidth() * crop.getHeight()];
        for (int y = 0; y < crop.getHeight(); y++) {
            for (int x = 0; x < crop.getWidth(); x++) {
                Color c = new Color(crop.getRGB(x, y));
                alpha[y * crop.getWidth() + x] = c.getRed() > 180 && c.getGreen() < 80 ? 1f : 0f;
            }
        }
        return alpha;
    };

    private static Cutout cutout(AnimalDetector detector, ForegroundSegmenter segmenter, Cutout.Background background) {
        Cutout.Rules rules = new Cutout.Rules(0.40f, 0.15, 0.85, 1024, 0.05, 0.95, true, background);
        return new Cutout(detector, segmenter, rules);
    }

    @Test
    void cutsTheAnimalOutCentredOnASquareWithTransparentSurroundings() {
        Cutout.Result result = cutout(finds(ANIMAL), RED_IS_FOREGROUND, Cutout.Background.REMOVE)
                .make(photo(800, 600, ANIMAL, null));

        assertThat(result.status()).isEqualTo("APPLIED");
        BufferedImage out = result.image();
        assertThat(out.getWidth()).isEqualTo(out.getHeight()).isEqualTo((int) Math.ceil(200 / 0.85));
        assertThat(alpha(out, 0, 0)).as("corner").isZero();
        assertThat(alpha(out, out.getWidth() / 2, out.getHeight() / 2)).as("centre").isEqualTo(255);
        int[] opaque = opaqueBounds(out);
        int opaqueWidth = opaque[2] - opaque[0] + 1;
        assertThat((double) opaqueWidth / out.getWidth()).as("fill").isCloseTo(0.85, org.assertj.core.data.Offset.offset(0.02));
        assertThat((opaque[0] + opaque[2]) / 2.0).as("centred").isCloseTo(out.getWidth() / 2.0,
                org.assertj.core.data.Offset.offset(2.0));
        assertThat(result.foreground()).isBetween(0.05, 0.95);
        assertThat(result.crop()).isEqualTo(ANIMAL.grow(0.15).clip(800, 600));
    }

    @Test
    void anAnimalAtTheBorderIsStillCentred() {
        Box corner = new Box(0, 0, 200, 150);
        Cutout.Result result = cutout(finds(corner), RED_IS_FOREGROUND, Cutout.Background.REMOVE)
                .make(photo(800, 600, corner, null));

        assertThat(result.status()).isEqualTo("APPLIED");
        int[] opaque = opaqueBounds(result.image());
        assertThat((opaque[0] + opaque[2]) / 2.0).isCloseTo(result.image().getWidth() / 2.0,
                org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    void keepsOnlyTheLargestConnectedRegion() {
        Box blob = new Box(505, 330, 525, 350); // a red patch inside the margin, apart from the animal
        Cutout.Result result = cutout(finds(ANIMAL), RED_IS_FOREGROUND, Cutout.Background.REMOVE)
                .make(photo(800, 600, ANIMAL, blob));

        assertThat(result.status()).isEqualTo("APPLIED");
        // with the patch the tight box would be 225 px wide and the square 265; without it, the animal's 236
        assertThat(result.image().getWidth()).isEqualTo((int) Math.ceil(200 / 0.85));
        int[] opaque = opaqueBounds(result.image());
        assertThat(opaque[2] - opaque[0] + 1).as("opaque width").isEqualTo(200);
    }

    @Test
    void keepModeCropsAndPadsWithoutSegmenting() {
        ForegroundSegmenter mustNotRun = crop -> {
            throw new AssertionError("background is kept: no segmentation");
        };
        Cutout.Result result = cutout(finds(ANIMAL), mustNotRun, Cutout.Background.KEEP).make(photo(800, 600, ANIMAL, null));

        assertThat(result.status()).isEqualTo("APPLIED");
        BufferedImage out = result.image();
        assertThat(out.getWidth()).isEqualTo(out.getHeight());
        assertThat(alpha(out, out.getWidth() / 2, out.getHeight() / 2)).isEqualTo(255);
        assertThat(alpha(out, out.getWidth() / 2, 1)).as("above the crop: padding").isZero();
        assertThat(alpha(out, 1, out.getHeight() / 2)).as("grey background beside the animal is kept").isEqualTo(255);
    }

    @Test
    void bigCutoutsAreReducedToTheMaximumSide() {
        Box big = new Box(300, 300, 2100, 2100);
        Cutout.Result result = cutout(finds(big), RED_IS_FOREGROUND, Cutout.Background.REMOVE)
                .make(photo(2400, 2400, big, null));

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.image().getWidth()).isEqualTo(1024);
        assertThat(result.image().getHeight()).isEqualTo(1024);
    }

    @Test
    void fallsBackInsteadOfThrowing() {
        DecodedPhoto picture = photo(800, 600, ANIMAL, null);
        ForegroundSegmenter nothing = crop -> new float[crop.getWidth() * crop.getHeight()];
        ForegroundSegmenter everything = crop -> {
            float[] all = new float[crop.getWidth() * crop.getHeight()];
            java.util.Arrays.fill(all, 1f);
            return all;
        };
        ForegroundSegmenter flat = crop -> null;
        ForegroundSegmenter broken = crop -> {
            throw new PreprocessException("session closed");
        };

        assertThat(cutout(image -> List.of(), RED_IS_FOREGROUND, Cutout.Background.REMOVE).make(picture).status())
                .isEqualTo("FALLBACK:NO_ANIMAL");
        assertThat(cutout(image -> {
            throw new PreprocessException("detector down");
        }, RED_IS_FOREGROUND, Cutout.Background.REMOVE).make(picture).status()).isEqualTo("FALLBACK:ERROR");
        assertThat(cutout(finds(ANIMAL), nothing, Cutout.Background.REMOVE).make(picture).status())
                .isEqualTo("FALLBACK:MASK_EMPTY");
        assertThat(cutout(finds(ANIMAL), flat, Cutout.Background.REMOVE).make(picture).status())
                .isEqualTo("FALLBACK:MASK_EMPTY");
        assertThat(cutout(finds(ANIMAL), everything, Cutout.Background.REMOVE).make(picture).status())
                .isEqualTo("FALLBACK:MASK_FULL");
        Cutout.Result failed = cutout(finds(ANIMAL), broken, Cutout.Background.REMOVE).make(picture);
        assertThat(failed.status()).isEqualTo("FALLBACK:ERROR");
        assertThat(failed.image()).isNull();
        assertThat(failed.detail()).contains("session closed");
    }

    @Test
    void writesAnRgbaPngWithoutMetadataThroughATemporaryFile(@TempDir Path dir) throws Exception {
        Cutout.Result result = cutout(finds(ANIMAL), RED_IS_FOREGROUND, Cutout.Background.REMOVE)
                .make(photo(800, 600, ANIMAL, null));
        Path target = dir.resolve("job-1.png");

        Cutout.writePng(result.image(), target);

        BufferedImage back = ImageIO.read(target.toFile());
        assertThat(back.getColorModel().hasAlpha()).isTrue();
        assertThat(back.getWidth()).isEqualTo(result.image().getWidth());
        assertThat(alpha(back, 0, 0)).isZero();
        String raw = new String(Files.readAllBytes(target), StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("eXIf").doesNotContain("Exif");
        try (var files = Files.list(dir)) {
            assertThat(files.toList()).containsExactly(target);
        }
    }

    @Test
    void versionNamesTheModelsAndTheSettings() {
        AnimalDetector detector = new AnimalDetector() {
            public List<Detection> detect(BufferedImage image) {
                return List.of();
            }

            public String name() {
                return "yolox_s#1a2b3c4d";
            }
        };
        ForegroundSegmenter segmenter = new ForegroundSegmenter() {
            public float[] segment(BufferedImage crop) {
                return null;
            }

            public String name() {
                return "u2net#5e6f7a8b";
            }
        };

        String remove = cutout(detector, segmenter, Cutout.Background.REMOVE).version();
        String keep = cutout(detector, segmenter, Cutout.Background.KEEP).version();

        assertThat(remove).contains("bg=remove", "yolox_s#1a2b3c4d", "u2net#5e6f7a8b", "m=0.15", "f=0.85", "s=1024")
                .hasSizeLessThanOrEqualTo(128);
        assertThat(keep).contains("bg=keep", "yolox_s#1a2b3c4d").doesNotContain("u2net");
    }

    private static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xFF;
    }

    /** min x, min y, max x, max y of the pixels with alpha >= 128. */
    private static int[] opaqueBounds(BufferedImage image) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1};
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alpha(image, x, y) >= 128) {
                    b[0] = Math.min(b[0], x);
                    b[1] = Math.min(b[1], y);
                    b[2] = Math.max(b[2], x);
                    b[3] = Math.max(b[3], y);
                }
            }
        }
        return b;
    }
}
