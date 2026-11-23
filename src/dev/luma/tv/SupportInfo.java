package dev.luma.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class SupportInfo {
    private static final String SEPARATOR_SPACE = "[\\p{Z}\\t-\\r\\u0085]*";
    private static final Pattern SEPARATOR = Pattern.compile(SEPARATOR_SPACE + "\\|" + SEPARATOR_SPACE);
    static final String SOURCE_IMAGE = "qr_source";

    static final class Coin {
        final String name, network, address, image;

        Coin(String[] fields, int index) {
            name = fields[0];
            network = fields.length > 1 ? fields[1] : "";
            address = fields.length > 2 ? fields[2] : "";
            image = "qr_" + index;
        }
    }

    static List<Coin> coins(InputStream input) throws IOException {
        List<Coin> coins = new ArrayList<>();
        for (String[] fields : rows(input)) coins.add(new Coin(fields, coins.size()));
        return coins;
    }

    static String about(InputStream input, String key) throws IOException {
        String prefix = key + "=";
        for (String[] fields : rows(input)) {
            if (fields[0].startsWith(prefix)) return fields[0].substring(prefix.length());
        }
        return "";
    }

    private static List<String[]> rows(InputStream input) throws IOException {
        List<String[]> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) result.add(SEPARATOR.split(line, -1));
            }
        }
        return result;
    }
}
