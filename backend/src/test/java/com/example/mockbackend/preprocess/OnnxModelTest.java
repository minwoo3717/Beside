package com.example.mockbackend.preprocess;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

/** Parts of OnnxModel that need no model file. */
class OnnxModelTest {

    @Test
    void readsTheFileVersionOfAWindowsDll() {
        // padding, then VS_FIXEDFILEINFO: signature, structure version, file version 14.29 (MS) and 30038.0 (LS)
        ByteBuffer pe = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        pe.position(21);
        pe.putInt(0xFEEF04BD).putInt(0x00010000).putInt((14 << 16) | 29).putInt(30038 << 16);

        assertThat(OnnxModel.peFileVersion(pe.array())).containsExactly(14, 29);
        assertThat(OnnxModel.peFileVersion(new byte[64])).isNull();
    }
}
