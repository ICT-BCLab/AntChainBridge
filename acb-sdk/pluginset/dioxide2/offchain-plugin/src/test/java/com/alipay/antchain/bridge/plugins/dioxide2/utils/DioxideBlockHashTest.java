package com.alipay.antchain.bridge.plugins.dioxide2.utils;

import cn.hutool.core.util.HexUtil;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertArrayEquals;

public class DioxideBlockHashTest {
    @Test
    public void nativeArchivedBlockMatchesIndependentBase32Decoder() {
        // Global shard block 8026083; expected bytes also decoded with Python's RFC4648 codec.
        assertArrayEquals(HexUtil.decodeHex("bced6b7c7dabdedf882ab2f26a1e9807b7fe559e6fb2c6e731933ae4a123265b"),
                DioxideBlockHash.decode("qkpppz3xnffdz21apbs6m7mr0yvzwncydyscdsshjcxe98934sdg"));
    }

    @Test
    public void preservesLeadingZeroAndAllOneDigestBytes() {
        assertArrayEquals(new byte[32], DioxideBlockHash.decode("0".repeat(52)));
        byte[] ones = new byte[32];
        Arrays.fill(ones, (byte) 0xff);
        assertArrayEquals(ones, DioxideBlockHash.decode("z".repeat(51) + "g"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonzeroUnusedPaddingBits() {
        DioxideBlockHash.decode("0".repeat(51) + "1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCharactersOutsideNativeAlphabet() {
        DioxideBlockHash.decode("o" + "0".repeat(51));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTruncatedDigest() {
        DioxideBlockHash.decode("0".repeat(51));
    }
}
