package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class YoloxCodecTest {
    private static final int[] PETS = {YoloxCodec.COCO_CAT, YoloxCodec.COCO_DOG};
    private static final int COLS = 85;

    @Test
    void letterboxScalesDownAndPadsTheBottomRightWith114() {
        BufferedImage photo = Pictures.halves(200, 100, BufferedImage.TYPE_3BYTE_BGR); // red left, blue right
        int s = 64;

        YoloxCodec.Letterbox in = YoloxCodec.letterbox(photo, s, YoloxCodec.ChannelOrder.BGR, false);

        assertThat(in.ratio()).isEqualTo(0.32f); // min(64/100, 64/200)
        assertThat(in.chw()).hasSize(3 * s * s);
        // pasted at the top-left: 64 x 32, red at (5,5), blue at (60,5); below row 32 is padding
        assertThat(at(in, 0, 5, 5)).as("B of red").isCloseTo(30f, within(20f));
        assertThat(at(in, 2, 5, 5)).as("R of red").isCloseTo(220f, within(20f));
        assertThat(at(in, 0, 60, 5)).as("B of blue").isCloseTo(220f, within(20f));
        for (int c = 0; c < 3; c++) {
            assertThat(at(in, c, 5, 40)).as("padding channel %d", c).isEqualTo(114f);
            assertThat(at(in, c, 63, 63)).isEqualTo(114f);
        }
    }

    @Test
    void channelOrderAndLegacyNormalisationAreSettings() {
        BufferedImage photo = Pictures.halves(200, 100, BufferedImage.TYPE_3BYTE_BGR);

        YoloxCodec.Letterbox rgb = YoloxCodec.letterbox(photo, 64, YoloxCodec.ChannelOrder.RGB, false);
        assertThat(at(rgb, 0, 5, 5)).as("R first").isCloseTo(220f, within(20f));

        YoloxCodec.Letterbox legacy = YoloxCodec.letterbox(photo, 64, YoloxCodec.ChannelOrder.RGB, true);
        assertThat(at(legacy, 0, 5, 40)).as("padding, R: (114/255 - 0.485) / 0.229")
                .isCloseTo((114f / 255f - 0.485f) / 0.229f, within(1e-4f));
    }

    @Test
    void decodesTheGridAndKeepsOnlyDogsAndCats() {
        int s = 64; // strides 8, 16, 32 -> 8x8 + 4x4 + 2x2 = 84 rows
        float[] out = new float[84 * COLS];
        // a dog at stride 16, cell (gx 1, gy 2) -> row 64 + 2*4 + 1 = 73
        cell(out, 73, 0.5f, 0.5f, (float) Math.log(1.25), (float) Math.log(2.0), 0.9f, 16, 0.8f);
        // a person, very sure: not a pet
        cell(out, 10, 0.5f, 0.5f, 0f, 0f, 0.99f, 0, 0.99f);
        // a cat below the candidate score: 0.3 * 0.5 = 0.15
        cell(out, 20, 0.5f, 0.5f, 0f, 0f, 0.3f, 15, 0.5f);

        List<Detection> found = YoloxCodec.decode(out, 84, COLS, s, 0.5f, 128, 128, PETS, 0.25f, false);

        assertThat(found).hasSize(1);
        Detection dog = found.get(0);
        assertThat(dog.classId()).isEqualTo(YoloxCodec.COCO_DOG);
        assertThat(dog.score()).isCloseTo(0.72f, within(1e-5f));
        // centre (1.5*16, 2.5*16) = (24, 40), size 20 x 32 in the 64 input; / ratio 0.5 -> original pixels
        assertBox(dog.box(), 28, 48, 68, 112);
    }

    @Test
    void decodedExportsAreReadAsPixelBoxesAndClipped() {
        float[] out = new float[2 * COLS];
        cell(out, 0, 50f, 40f, 20f, 20f, 0.9f, 15, 0.9f);   // already cx, cy, w, h in input pixels
        cell(out, 1, 60f, 60f, 40f, 40f, 0.9f, 16, 0.9f);   // reaches past the 64 x 64 picture

        List<Detection> found = YoloxCodec.decode(out, 2, COLS, 64, 1f, 64, 64, PETS, 0.25f, true);

        assertThat(found).hasSize(2);
        assertBox(found.get(0).box(), 40, 30, 60, 50);
        assertBox(found.get(1).box(), 40, 40, 64, 64);
    }

    @Test
    void refusesAnOutputOfTheWrongShape() {
        assertThatThrownBy(() -> YoloxCodec.decode(new float[83 * COLS], 83, COLS, 64, 1f, 64, 64, PETS, 0.25f, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("84");
        // 5 + 16 columns: classes 0..15, an output without the dog column
        assertThatThrownBy(() -> YoloxCodec.decode(new float[84 * 21], 84, 21, 64, 1f, 64, 64, PETS, 0.25f, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("class 16");
    }

    @Test
    void suppressionIgnoresTheClassAndDropsBoxesInsideABetterOne() {
        Detection dog = new Detection(new Box(0, 0, 100, 100), 16, 0.9f);
        Detection sameAnimalAsCat = new Detection(new Box(5, 5, 100, 100), 15, 0.6f);   // IoU 0.9025
        Detection headOfTheDog = new Detection(new Box(10, 10, 40, 40), 16, 0.5f);       // inside the dog box
        Detection secondDog = new Detection(new Box(150, 0, 250, 100), 16, 0.8f);         // apart
        Detection touching = new Detection(new Box(80, 0, 160, 100), 15, 0.7f);           // 25 % inside the first

        List<Detection> kept = YoloxCodec.nms(List.of(touching, headOfTheDog, secondDog, sameAnimalAsCat, dog), 0.45f, 0.7f);

        assertThat(kept).containsExactly(dog, secondDog, touching);
    }

    @Test
    void boxGeometry() {
        Box a = new Box(0, 0, 10, 10);
        Box b = new Box(5, 5, 15, 15);
        assertThat(a.area()).isEqualTo(100f);
        assertThat(a.intersection(b)).isEqualTo(25f);
        assertThat(a.iou(b)).isCloseTo(25f / 175f, within(1e-6f));
        assertThat(new Box(0, 0, 10, 20).longSide()).isEqualTo(20f);
        assertBox(a.grow(0.5).clip(12, 12), 0, 0, 12, 12);
        assertBox(a.grow(0.1), -1, -1, 11, 11);
        assertBox(a.scale(2), 0, 0, 20, 20);
        assertThat(new Box(5, 5, 4, 4).area()).isZero();
    }

    private static float at(YoloxCodec.Letterbox in, int channel, int x, int y) {
        int s = in.size();
        return in.chw()[channel * s * s + y * s + x];
    }

    private static void cell(float[] out, int row, float x, float y, float w, float h, float obj, int cls, float clsScore) {
        int o = row * COLS;
        out[o] = x;
        out[o + 1] = y;
        out[o + 2] = w;
        out[o + 3] = h;
        out[o + 4] = obj;
        out[o + 5 + cls] = clsScore;
    }

    private static void assertBox(Box box, float x1, float y1, float x2, float y2) {
        assertThat(box.x1()).as("x1 of %s", box).isCloseTo(x1, within(1e-3f));
        assertThat(box.y1()).as("y1 of %s", box).isCloseTo(y1, within(1e-3f));
        assertThat(box.x2()).as("x2 of %s", box).isCloseTo(x2, within(1e-3f));
        assertThat(box.y2()).as("y2 of %s", box).isCloseTo(y2, within(1e-3f));
    }
}
