package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    private ThemeFiles() {}

    static String path(List<String> segments) {
        if (segments == null) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0)) && collectionImage(segments.get(1))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        return null;
    }

    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0));
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
