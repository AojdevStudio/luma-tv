package dev.luma.tv;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class SupportInfoTest {
    public static void main(String[] args) throws Exception {
        String row = "Coin | Network |\u00a0value\u00a0inside";
        SupportInfo.Coin coin = SupportInfo.coins(
                new ByteArrayInputStream(row.getBytes(StandardCharsets.UTF_8))).get(0);
        if (!coin.address.equals("value\u00a0inside")) {
            throw new AssertionError("Remove separator whitespace and preserve spaces inside the address");
        }
    }
}
