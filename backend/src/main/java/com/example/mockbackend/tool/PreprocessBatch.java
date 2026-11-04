package com.example.mockbackend.tool;

import com.example.mockbackend.preprocess.AnimalDetector;
import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import com.example.mockbackend.preprocess.Box;
import com.example.mockbackend.preprocess.Cutout;
import com.example.mockbackend.preprocess.DecodedPhoto;
import com.example.mockbackend.preprocess.ForegroundSegmenter;
import com.example.mockbackend.preprocess.ImageDecoding;
import com.example.mockbackend.preprocess.OnnxSaliencySegmenter;
import com.example.mockbackend.preprocess.PhotoCheck;
import com.example.mockbackend.preprocess.PreprocessException;
import com.example.mockbackend.preprocess.UnreadableImageException;
import com.example.mockbackend.preprocess.YoloxDetector;
import com.example.mockbackend.service.preprocess.PreprocessProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Runs the first-photo check and both cutouts (crop and pad, background removed) on a folder of photos, with the
 * server's own settings, and writes what each step saw: for slides (before and after), for tuning the proposed
 * thresholds on real photos, and as the input set of the 3D comparison (backend/README.md "폴더 일괄 도구").
 *
 * <pre>.\gradlew.bat preprocessBatch --args="--in &lt;folder&gt; --out &lt;folder&gt; [--models &lt;dir&gt;]
 *     [--set preprocess.check.min-sharpness=80 ...] [--ctrl-size 512]"</pre>
 */
public final class PreprocessBatch {
    private static final List<String> EXTENSIONS = List.of(".jpg", ".jpeg", ".png", ".webp");

    /** Every COCO class above a score (YoloxDetector::detectAll): what else the detector saw. */
    @FunctionalInterface
    public interface Diagnostics {
        List<Detection> detectAll(BufferedImage image, float minScore) throws PreprocessException;
    }

    /** One line of report.csv (BatchReport.CSV_HEADER). */
    public record Row(String file, String folder, int width, int height, String decision, String detail, int animals,
                      String mainClass, float mainScore, int mainLongSidePx, int brightness, double sharpness,
                      String bestOtherClass, float bestOtherScore, long decodeMs, long detectMs, long checkMs,
                      String cutout, long cutoutMs, long segmentMs, double foreground, String cropPad) {
    }

    private PreprocessBatch() {
    }

    public static void main(String[] args) throws Exception {
        Path in = null;
        Path out = null;
        String models = null;
        int ctrlSize = 512;
        List<String> sets = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--in" -> in = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--models" -> models = args[++i];
                case "--set" -> sets.add(args[++i]);
                case "--ctrl-size" -> ctrlSize = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i] + "; usage: --in <folder> "
                        + "--out <folder> [--models <dir>] [--set key=value]... [--ctrl-size 512]");
            }
        }
        if (in == null || out == null || !Files.isDirectory(in)) {
            throw new IllegalArgumentException("--in <existing folder> and --out <folder> are required");
        }
        PreprocessProperties props = settings(sets, models);
        System.out.println("models in " + props.modelsPath().toAbsolutePath());
        try (YoloxDetector detector = YoloxDetector.load(props.detectorSpec());
             OnnxSaliencySegmenter segmenter = OnnxSaliencySegmenter.load(props.segmenterModel(),
                     props.segmenterSpec(), props.getSegmenter().getThreads(), props.getSegmenter().isCpuArena())) {
            List<Row> rows = run(in, out, props, detector, segmenter, detector::detectAll, ctrlSize);
            long passed = rows.stream().filter(r -> r.decision().equals("PASS")).count();
            System.out.printf(Locale.ROOT, "%d photos, %d pass, %d refused -> %s%n", rows.size(), passed,
                    rows.size() - passed, out.resolve("index.html").toAbsolutePath());
        }
    }

    /**
     * The server's settings: application.properties, then application-real.properties, then PREPROCESS_MODELSDIR,
     * {@code modelsDir} and the --set overrides.
     */
    public static PreprocessProperties settings(List<String> sets, String modelsDir) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String file : List.of("/application.properties", "/application-real.properties")) {
            Properties p = new Properties();
            try (InputStream stream = PreprocessBatch.class.getResourceAsStream(file)) {
                if (stream != null) {
                    p.load(stream);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
            p.forEach((k, v) -> values.put(k.toString(), v));
        }
        String env = System.getenv("PREPROCESS_MODELSDIR");
        if (env != null && !env.isBlank()) {
            values.put("preprocess.models-dir", env);
        }
        if (modelsDir != null) {
            values.put("preprocess.models-dir", modelsDir);
        }
        for (String set : sets) {
            int eq = set.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("--set wants key=value, got " + set);
            }
            values.put(set.substring(0, eq).trim(), set.substring(eq + 1).trim());
        }
        return new Binder(new MapConfigurationPropertySource(values)).bind("preprocess", PreprocessProperties.class)
                .orElseGet(PreprocessProperties::new);
    }

    /** Checks and cuts out every photo of {@code in} (by name), writing to {@code out}; returns the report rows. */
    public static List<Row> run(Path in, Path out, PreprocessProperties props, AnimalDetector detector,
                                ForegroundSegmenter segmenter, Diagnostics diagnostics, int ctrlSize) throws IOException {
        Files.createDirectories(out);
        List<Path> photos;
        try (Stream<Path> files = Files.list(in)) {
            photos = files.filter(Files::isRegularFile)
                    .filter(f -> EXTENSIONS.stream().anyMatch(e -> f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(e)))
                    .sorted().toList();
        }
        PhotoCheck check = new PhotoCheck(detector, props.checkRules(), props.decodeLimits(),
                props.getCheck().getDecodeLongSide());
        Cutout.Rules rules = props.cutoutRules();
        Cutout remove = new Cutout(detector, segmenter, withBackground(rules, Cutout.Background.REMOVE));
        Cutout keep = new Cutout(detector, null, withBackground(rules, Cutout.Background.KEEP));

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            Path photo = photos.get(i);
            String folder = String.format(Locale.ROOT, "%02d_%s", i + 1, safe(stem(photo.getFileName().toString())));
            Path folderPath = Files.createDirectories(out.resolve(folder));
            Row row = one(photo, folder, folderPath, props, check, remove, keep, diagnostics, ctrlSize);
            rows.add(row);
            System.out.printf(Locale.ROOT, "%-28s %-20s cutout %-22s %s%n", row.file(), row.decision(), row.cutout(),
                    row.detail());
        }
        BatchReport.write(out, rows, props, detector.name(), segmenter == null ? "-" : segmenter.name(), ctrlSize,
                remove.version(), keep.version());
        return rows;
    }

    private static Row one(Path photo, String folder, Path dir, PreprocessProperties props, PhotoCheck check,
                           Cutout remove, Cutout keep, Diagnostics diagnostics, int ctrlSize) throws IOException {
        String file = photo.getFileName().toString();
        DecodedPhoto decoded;
        try {
            decoded = ImageDecoding.decode(photo, props.getCheck().getDecodeLongSide(), props.decodeLimits());
        } catch (UnreadableImageException e) {
            return new Row(file, folder, 0, 0, PhotoCheck.Reason.UNREADABLE_IMAGE.name(), e.getMessage(), 0, "", 0, 0,
                    -1, -1, "", 0, 0, 0, 0, "-", 0, 0, -1, "-");
        }
        PhotoCheck.Result result;
        try {
            result = check.check(decoded);
        } catch (PreprocessException e) {
            return new Row(file, folder, decoded.width(), decoded.height(), "ERROR", e.getMessage(), 0, "", 0, 0, -1,
                    -1, "", 0, decoded.decodeMs(), 0, 0, "-", 0, 0, -1, "-");
        }
        List<Detection> everything;
        try {
            everything = diagnostics.detectAll(decoded.image(), 0.25f);
        } catch (PreprocessException e) {
            everything = List.of();
        }
        Detection other = everything.stream()
                .filter(d -> d.classId() != com.example.mockbackend.preprocess.YoloxCodec.COCO_CAT
                        && d.classId() != com.example.mockbackend.preprocess.YoloxCodec.COCO_DOG)
                .findFirst().orElse(null);
        String decision = result.accepted() ? "PASS" : result.reason().name();

        ImageIO.write(decoded.image(), "jpeg", dir.resolve("original.jpg").toFile());
        ImageIO.write(annotate(decoded.image(), result, everything, decision), "jpeg", dir.resolve("detect.jpg").toFile());
        ImageIO.write(stretch(decoded.image(), ctrlSize), "png", dir.resolve("model_in_before.png").toFile());

        DecodedPhoto big = props.getCutout().getDecodeLongSide() == props.getCheck().getDecodeLongSide() ? decoded
                : decodeQuietly(photo, props);
        Cutout.Result removed = big == null ? null : remove.make(big);
        Cutout.Result kept = big == null ? null : keep.make(big);
        if (kept != null && kept.applied()) {
            Cutout.writePng(kept.image(), dir.resolve("crop_pad.png"));
        }
        if (removed != null && removed.applied()) {
            Cutout.writePng(removed.image(), dir.resolve("cutout.png"));
            BufferedImage onWhite = onWhite(removed.image());
            ImageIO.write(onWhite, "jpeg", dir.resolve("on_white.jpg").toFile());
            ImageIO.write(stretch(onWhite, ctrlSize), "png", dir.resolve("model_in_after.png").toFile());
        }

        Detection main = result.animal();
        return new Row(file, folder, decoded.width(), decoded.height(), decision, result.detail(),
                result.animals().size(), main == null ? "" : BatchReport.cocoName(main.classId()),
                main == null ? 0 : main.score(),
                main == null ? 0 : (int) Math.round(main.box().longSide() * decoded.scale()), result.brightness(),
                result.sharpness(), other == null ? "" : BatchReport.cocoName(other.classId()),
                other == null ? 0 : other.score(), decoded.decodeMs(), result.detectMs(), result.totalMs(),
                removed == null ? "FALLBACK:DECODE_FAILED" : removed.status(), removed == null ? 0 : removed.totalMs(),
                removed == null ? 0 : removed.segmentMs(), removed == null ? -1 : removed.foreground(),
                kept == null ? "FALLBACK:DECODE_FAILED" : kept.status());
    }

    private static DecodedPhoto decodeQuietly(Path photo, PreprocessProperties props) {
        try {
            return ImageDecoding.decode(photo, props.getCutout().getDecodeLongSide(), props.decodeLimits());
        } catch (UnreadableImageException e) {
            return null;
        }
    }

    private static Cutout.Rules withBackground(Cutout.Rules r, Cutout.Background background) {
        return new Cutout.Rules(r.minAnimalScore(), r.margin(), r.fill(), r.maxSide(), r.minForeground(),
                r.maxForeground(), r.largestComponentOnly(), background);
    }

    /** Boxes: the main animal green, other dogs and cats orange, everything else grey; the decision on top. */
    private static BufferedImage annotate(BufferedImage image, PhotoCheck.Result result, List<Detection> everything,
                                          String decision) {
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        try {
            g.drawImage(image, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int unit = Math.max(2, Math.max(image.getWidth(), image.getHeight()) / 300);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, unit * 7));
            for (Detection d : everything) {
                if (result.animals().stream().noneMatch(a -> a.box().iou(d.box()) > 0.9f)) {
                    box(g, d.box(), new Color(160, 160, 160), unit,
                            String.format(Locale.ROOT, "%s %.2f", BatchReport.cocoName(d.classId()), d.score()));
                }
            }
            for (Detection d : result.animals()) {
                boolean main = d == result.animal();
                box(g, d.box(), main ? new Color(40, 200, 60) : new Color(255, 140, 0), unit,
                        String.format(Locale.ROOT, "%s %.2f", BatchReport.cocoName(d.classId()), d.score()));
            }
            g.setColor(new Color(0, 0, 0, 170));
            g.fillRect(0, 0, copy.getWidth(), unit * 10);
            g.setColor(decision.equals("PASS") ? new Color(120, 255, 140) : new Color(255, 120, 120));
            g.drawString(decision, unit * 2, unit * 8);
        } finally {
            g.dispose();
        }
        return copy;
    }

    private static void box(Graphics2D g, Box b, Color color, int unit, String label) {
        g.setColor(color);
        g.setStroke(new BasicStroke(unit));
        g.drawRect(Math.round(b.x1()), Math.round(b.y1()), Math.round(b.width()), Math.round(b.height()));
        g.drawString(label, Math.round(b.x1()) + unit, Math.max(unit * 18, Math.round(b.y1()) - unit));
    }

    /** As Python's load_rgb: transparent pixels become white. */
    static BufferedImage onWhite(BufferedImage rgba) {
        BufferedImage white = new BufferedImage(rgba.getWidth(), rgba.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = white.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, white.getWidth(), white.getHeight());
            g.drawImage(rgba, 0, 0, null);
        } finally {
            g.dispose();
        }
        return white;
    }

    /** As AnimalLift's Resize((ctrl, ctrl)): stretched to a square, aspect not kept. */
    static BufferedImage stretch(BufferedImage image, int size) {
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = square.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(image, 0, 0, size, size, null);
        } finally {
            g.dispose();
        }
        return square;
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String safe(String name) {
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }
}
