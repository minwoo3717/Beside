package com.example.mockbackend.tool;

import com.example.mockbackend.service.preprocess.PreprocessProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

/** report.csv, index.html and settings.txt of the batch tool. */
final class BatchReport {
    static final String CSV_HEADER = "file,width,height,decision,detail,animals,mainClass,mainScore,mainLongSidePx,"
            + "brightnessP90,sharpness,bestOtherClass,bestOtherScore,decodeMs,detectMs,checkMs,cutout,cutoutMs,"
            + "segmentMs,foreground,cropPad";

    /** COCO 80 classes in the order of the YOLOX output (15 cat, 16 dog). */
    private static final String[] COCO = {"person", "bicycle", "car", "motorcycle", "airplane", "bus", "train",
            "truck", "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag",
            "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl",
            "banana", "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard",
            "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors",
            "teddy bear", "hair drier", "toothbrush"};

    private BatchReport() {
    }

    static String cocoName(int id) {
        return id >= 0 && id < COCO.length ? COCO[id] : "class " + id;
    }

    static void write(Path out, List<PreprocessBatch.Row> rows, PreprocessProperties props, String detector,
                      String segmenter, int ctrlSize, String removeVersion, String keepVersion) throws IOException {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (PreprocessBatch.Row r : rows) {
            csv.append(String.join(",", cell(r.file()), String.valueOf(r.width()), String.valueOf(r.height()),
                    cell(r.decision()), cell(r.detail()), String.valueOf(r.animals()), cell(r.mainClass()),
                    num(r.mainScore()), String.valueOf(r.mainLongSidePx()), String.valueOf(r.brightness()),
                    num(r.sharpness()), cell(r.bestOtherClass()), num(r.bestOtherScore()), String.valueOf(r.decodeMs()),
                    String.valueOf(r.detectMs()), String.valueOf(r.checkMs()), cell(r.cutout()),
                    String.valueOf(r.cutoutMs()), String.valueOf(r.segmentMs()), num(r.foreground()),
                    cell(r.cropPad()))).append('\n');
        }
        Files.writeString(out.resolve("report.csv"), csv, StandardCharsets.UTF_8);

        String settings = "when: " + OffsetDateTime.now() + "\n"
                + "java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
                + "os: " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
                + Runtime.getRuntime().availableProcessors() + " processors\n"
                + "detector: " + detector + "\nsegmenter: " + segmenter + "\n"
                + "check: " + props.checkRules() + "\n"
                + "decode: " + props.decodeLimits() + ", check long side " + props.getCheck().getDecodeLongSide()
                + ", cutout long side " + props.getCutout().getDecodeLongSide() + "\n"
                + "detector settings: input " + props.getDetector().getInputSize() + ", classes "
                + props.getDetector().getClassIds() + ", candidate " + props.getDetector().getCandidateScore()
                + ", nms " + props.getDetector().getNmsIou() + ", containment " + props.getDetector().getContainment()
                + ", threads " + props.getDetector().getThreads() + "\n"
                + "segmenter settings: input " + props.getSegmenter().getInputSize() + ", mean "
                + props.getSegmenter().getMean() + ", std " + props.getSegmenter().getStd() + ", threads "
                + props.getSegmenter().getThreads() + "\n"
                + "cutout (remove): " + removeVersion + "\ncutout (keep): " + keepVersion + "\n"
                + "model input size for the before/after previews: " + ctrlSize
                + " (assumed: AnimalLift's ctrl_size is read from its checkpoint on the GPU PC)\n";
        Files.writeString(out.resolve("settings.txt"), settings, StandardCharsets.UTF_8);

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"ko\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>전처리 일괄 결과</title><style>")
                .append("body{font-family:system-ui,sans-serif;margin:16px;color:#222;background:#fff}")
                .append("table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:4px;vertical-align:top;font-size:12px}")
                .append("img{width:150px;display:block}.chk{background-color:#fff;background-image:")
                .append("linear-gradient(45deg,#ddd 25%,transparent 25%),linear-gradient(-45deg,#ddd 25%,transparent 25%),")
                .append("linear-gradient(45deg,transparent 75%,#ddd 75%),linear-gradient(-45deg,transparent 75%,#ddd 75%);")
                .append("background-size:16px 16px;background-position:0 0,0 8px,8px -8px,-8px 0}")
                .append(".pass{color:#070;font-weight:bold}.refused{color:#a00;font-weight:bold}</style></head><body>\n")
                .append("<h1>대표 사진 검사·전처리 일괄 결과</h1>\n<p>")
                .append(esc(detector)).append(" · ").append(esc(segmenter)).append(" · 모델 입력 ")
                .append(ctrlSize).append(" px(가정) · <a href=\"report.csv\">report.csv</a> · ")
                .append("<a href=\"settings.txt\">settings.txt</a></p>\n<table><tr><th>사진</th><th>판정</th>")
                .append("<th>원본</th><th>감지</th><th>지금 입력(늘림)</th><th>크롭+패딩</th><th>배경 제거</th>")
                .append("<th>새 입력</th></tr>\n");
        for (PreprocessBatch.Row r : rows) {
            String f = r.folder() + "/";
            html.append("<tr><td>").append(esc(r.file())).append("<br>").append(r.width()).append("×")
                    .append(r.height()).append("</td><td class=\"").append(r.decision().equals("PASS") ? "pass" : "refused")
                    .append("\">").append(esc(r.decision())).append("<br><small>").append(esc(r.detail()))
                    .append("</small><br><small>cutout ").append(esc(r.cutout())).append("</small></td>")
                    .append(img(out, f + "original.jpg", false)).append(img(out, f + "detect.jpg", false))
                    .append(img(out, f + "model_in_before.png", false)).append(img(out, f + "crop_pad.png", true))
                    .append(img(out, f + "cutout.png", true)).append(img(out, f + "model_in_after.png", false))
                    .append("</tr>\n");
        }
        html.append("</table>\n<p><small>체크무늬는 투명한 부분이다. '지금 입력'은 AnimalLift 가 지금 받는 늘린 사진, ")
                .append("'새 입력'은 배경을 지운 정사각 PNG 를 흰색에 합성해 같은 크기로 줄인 것이다.</small></p></body></html>\n");
        Files.writeString(out.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private static String img(Path out, String relative, boolean checker) {
        if (!Files.isRegularFile(out.resolve(relative))) {
            return "<td>-</td>";
        }
        return "<td" + (checker ? " class=\"chk\"" : "") + "><a href=\"" + relative + "\"><img src=\"" + relative
                + "\" alt=\"" + relative + "\"></a></td>";
    }

    private static String cell(String value) {
        String v = value == null ? "" : value;
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String num(double value) {
        return value < 0 ? "" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
