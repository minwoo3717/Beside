package com.example.mockbackend.preprocess;

import com.example.mockbackend.preprocess.AnimalDetector.Detection;
import com.example.mockbackend.preprocess.PhotoCheck.Reason;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoCheckTest {
    private static final Box SUBJECT = new Box(200, 150, 600, 450);   // 400 x 300 in an 800 x 600 photo
    private static final BufferedImage GOOD = Pictures.scene(800, 600, SUBJECT, true, 1.0);

    private static PhotoCheck check(Detection... found) {
        return new PhotoCheck(image -> List.of(found), PhotoCheck.Rules.DEFAULT, ImageDecoding.Limits.DEFAULT, 1600);
    }

    private static Detection dog(Box box, float score) {
        return new Detection(box, YoloxCodec.COCO_DOG, score);
    }

    private static Detection cat(Box box, float score) {
        return new Detection(box, YoloxCodec.COCO_CAT, score);
    }

    @Test
    void oneSharpWellLitAnimalPasses() throws Exception {
        PhotoCheck.Result result = check(dog(SUBJECT, 0.9f)).check(Pictures.decoded(GOOD));

        assertThat(result.accepted()).isTrue();
        assertThat(result.reason()).isNull();
        assertThat(result.animal().box()).isEqualTo(SUBJECT);
        assertThat(result.brightness()).isGreaterThanOrEqualTo(50);
        assertThat(result.sharpness()).isGreaterThan(50);
        assertThat(result.width()).isEqualTo(800);
        assertThat(result.height()).isEqualTo(600);
    }

    @Test
    void noDogOrCatAboveTheScore() throws Exception {
        assertThat(check().check(Pictures.decoded(GOOD)).reason()).isEqualTo(Reason.NO_ANIMAL_DETECTED);
        assertThat(check(dog(SUBJECT, 0.39f)).check(Pictures.decoded(GOOD)).reason())
                .isEqualTo(Reason.NO_ANIMAL_DETECTED);
    }

    @Test
    void twoAnimalsOfComparableSizeAreRejected() throws Exception {
        Detection second = cat(new Box(620, 300, 780, 450), 0.8f); // 160 x 150 = 20 % of the dog box
        PhotoCheck.Result result = check(dog(SUBJECT, 0.9f), second).check(Pictures.decoded(GOOD));

        assertThat(result.reason()).isEqualTo(Reason.TOO_MANY_ANIMALS);
        assertThat(result.animals()).hasSize(2);
        assertThat(result.detail()).contains("2 animals");
    }

    @Test
    void aSmallOrUnsureSecondAnimalDoesNotCount() throws Exception {
        Detection tiny = cat(new Box(700, 50, 760, 100), 0.95f);       // 3000 px^2 = 2.5 % of the dog: a picture on the wall
        Detection unsure = cat(new Box(620, 300, 780, 450), 0.35f);    // big enough but below 0.40
        PhotoCheck.Result result = check(tiny, dog(SUBJECT, 0.9f), unsure).check(Pictures.decoded(GOOD));

        assertThat(result.accepted()).isTrue();
        assertThat(result.animal().box()).as("the subject is the largest animal, not the best score").isEqualTo(SUBJECT);
    }

    @Test
    void theAnimalMustBeBigEnoughInOriginalPixels() throws Exception {
        Box small = new Box(300, 200, 400, 300); // 100 px long side
        BufferedImage image = Pictures.scene(800, 600, small, true, 1.0);

        assertThat(check(dog(small, 0.9f)).check(Pictures.decoded(image)).reason()).isEqualTo(Reason.ANIMAL_TOO_SMALL);
        // the same picture decoded at a quarter of a 3200 x 2400 original: the box is 400 original pixels
        DecodedPhoto reduced = new DecodedPhoto(image, 3200, 2400, 4.0, 1, "jpeg", 0);
        assertThat(check(dog(small, 0.9f)).check(reduced).accepted()).isTrue();
    }

    @Test
    void aSmallPhotoIsRejectedBeforeDetection() throws Exception {
        BufferedImage tiny = Pictures.scene(300, 200, new Box(50, 50, 250, 150), true, 1.0);
        PhotoCheck check = new PhotoCheck(image -> {
            throw new AssertionError("no detection on a photo that is too small");
        }, PhotoCheck.Rules.DEFAULT, ImageDecoding.Limits.DEFAULT, 1600);

        assertThat(check.check(Pictures.decoded(tiny)).reason()).isEqualTo(Reason.IMAGE_TOO_SMALL);
    }

    @Test
    void darkAnimalIsRejectedButABlackCatInGoodLightIsNot() throws Exception {
        BufferedImage dark = Pictures.scene(800, 600, SUBJECT, true, 0.15); // 235 -> 35
        assertThat(check(dog(SUBJECT, 0.9f)).check(Pictures.decoded(dark)).reason()).isEqualTo(Reason.IMAGE_TOO_DARK);

        // the top 70 % of the box is black fur; the rest of a real box is background around the cat
        BufferedImage blackCat = Pictures.blackSubject(800, 600, SUBJECT, 0.7);
        PhotoCheck.Result cat = check(cat(SUBJECT, 0.9f)).check(Pictures.decoded(blackCat));
        assertThat(cat.accepted()).as("brightness %d", cat.brightness()).isTrue();
    }

    @Test
    void blurryAnimalIsRejected() throws Exception {
        BufferedImage blurry = Pictures.scene(800, 600, SUBJECT, false, 1.0);
        PhotoCheck.Result result = check(dog(SUBJECT, 0.9f)).check(Pictures.decoded(blurry));

        assertThat(result.reason()).isEqualTo(Reason.IMAGE_TOO_BLURRY);
        assertThat(result.detail()).contains("sharpness");
    }

    @Test
    void theFirstFailingRuleWins() throws Exception {
        BufferedImage darkAndBlurry = Pictures.scene(800, 600, SUBJECT, false, 0.15);
        assertThat(check(dog(SUBJECT, 0.9f)).check(Pictures.decoded(darkAndBlurry)).reason())
                .isEqualTo(Reason.IMAGE_TOO_DARK);

        Box small = new Box(300, 200, 400, 300);
        Box otherSmall = new Box(450, 200, 550, 300);
        assertThat(check(dog(small, 0.9f), cat(otherSmall, 0.9f)).check(Pictures.decoded(GOOD)).reason())
                .isEqualTo(Reason.TOO_MANY_ANIMALS);
    }

    @Test
    void bytesThatAreNoImageAreUnreadable() throws Exception {
        PhotoCheck.Result result = check(dog(SUBJECT, 0.9f)).check("not a picture".getBytes());

        assertThat(result.reason()).isEqualTo(Reason.UNREADABLE_IMAGE);
        assertThat(result.animals()).isEmpty();
    }

    @Test
    void checksEncodedBytesEndToEnd() throws Exception {
        PhotoCheck.Result result = check(dog(SUBJECT, 0.9f)).check(Pictures.encode(GOOD, "png"));

        assertThat(result.accepted()).isTrue();
        assertThat(result.decodeMs()).isNotNegative();
    }

    @Test
    void aFailingDetectorIsTheServersFaultNotThePhotos() {
        PhotoCheck check = new PhotoCheck(image -> {
            throw new PreprocessException("session closed");
        }, PhotoCheck.Rules.DEFAULT, ImageDecoding.Limits.DEFAULT, 1600);

        assertThatThrownBy(() -> check.check(Pictures.decoded(GOOD))).isInstanceOf(PreprocessException.class);
    }

    @Test
    void everyReasonHasTheNameOfAnApiErrorCode() {
        assertThat(Reason.values()).extracting(Enum::name).containsExactly("UNREADABLE_IMAGE", "IMAGE_TOO_SMALL",
                "NO_ANIMAL_DETECTED", "TOO_MANY_ANIMALS", "ANIMAL_TOO_SMALL", "IMAGE_TOO_DARK", "IMAGE_TOO_BLURRY");
    }
}
