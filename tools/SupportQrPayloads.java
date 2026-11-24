package dev.luma.tv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SupportQrPayloads {
    public static void main(String[] args) throws IOException {
        Path raw = Paths.get("res/raw");
        Path output = Paths.get(args[0]);
        for (SupportInfo.Coin coin : SupportInfo.coins(Files.newInputStream(raw.resolve("support.txt")))) {
            if (coin.address.isEmpty()) throw new IOException("Set the address in the third field for " + coin.image);
            Files.write(output.resolve(coin.image), coin.address.getBytes(StandardCharsets.UTF_8));
        }
        String source = SupportInfo.about(Files.newInputStream(raw.resolve("about.txt")), "source");
        if (source.isEmpty()) throw new IOException("Set source= in res/raw/about.txt to the About screen URL.");
        Files.write(output.resolve(SupportInfo.SOURCE_IMAGE), source.getBytes(StandardCharsets.UTF_8));
    }
}
