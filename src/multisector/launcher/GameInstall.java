package multisector.launcher;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything the launcher needs to know about the Starsector install: where
 * it is, its saves, its enabled mods, and how to start it.
 *
 * Runs outside the game, so the mod sandbox doesn't apply and normal file
 * access is fine.
 */
final class GameInstall {

    final File root;
    final File saves;
    final File common;
    final File mods;

    private GameInstall(File root) {
        this.root = root;
        this.saves = new File(root, "saves");
        this.common = new File(saves, "common");
        this.mods = new File(root, "mods");
    }

    /**
     * Finds the game folder: the multisector.gameDir property if set (used by
     * "dev launcher"), otherwise three levels up from the jar
     * (game/mods/MultiSector/jars/MultiSector.jar).
     */
    static GameInstall locate() throws IOException {
        String prop = System.getProperty("multisector.gameDir");
        if (prop != null && !prop.isEmpty()) return check(new File(prop));
        try {
            File jar = new File(GameInstall.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File guess = jar.getParentFile().getParentFile().getParentFile().getParentFile();
            return check(guess);
        } catch (URISyntaxException | NullPointerException e) {
            throw new IOException("Can't work out where Starsector is installed.");
        }
    }

    private static GameInstall check(File dir) throws IOException {
        if (!new File(dir, "starsector-core").isDirectory()) {
            throw new IOException("\"" + dir + "\" doesn't look like a Starsector folder. "
                    + "The launcher must stay in Starsector\\mods\\MultiSector.");
        }
        return new GameInstall(dir.getCanonicalFile());
    }

    // ------------------------------------------------------------------ saves

    static final class SaveInfo {
        final File folder;
        final String characterName;
        final String saveDate;
        final String gameVersion;
        final long sizeBytes;
        /** The MultiSector version that last saved it, or "" if it hasn't been played with MultiSector. */
        final String modVersion;

        SaveInfo(File folder, String characterName, String saveDate, String gameVersion, long sizeBytes,
                 String modVersion) {
            this.folder = folder;
            this.characterName = characterName;
            this.saveDate = saveDate;
            this.gameVersion = gameVersion;
            this.sizeBytes = sizeBytes;
            this.modVersion = modVersion;
        }

        @Override
        public String toString() {
            return characterName + "   -   saved " + saveDate + "   (" + megabytes(sizeBytes) + ")   "
                    + (modVersion.isEmpty() ? "not played with MultiSector yet" : "MultiSector " + modVersion);
        }
    }

    /** Saves with a descriptor, newest first. */
    List<SaveInfo> listSaves() {
        List<SaveInfo> out = new ArrayList<SaveInfo>();
        File[] dirs = saves.listFiles();
        if (dirs == null) return out;
        Arrays.sort(dirs, new Comparator<File>() {
            public int compare(File a, File b) {
                return Long.compare(descriptorTime(b), descriptorTime(a));
            }
        });
        for (File d : dirs) {
            SaveInfo info = readSave(d);
            if (info != null) out.add(info);
        }
        return out;
    }

    static SaveInfo readSave(File dir) {
        File desc = new File(dir, "descriptor.xml");
        if (!dir.isDirectory() || !desc.isFile()) return null;
        try {
            String xml = new String(Files.readAllBytes(desc.toPath()), StandardCharsets.UTF_8);
            long size = 0;
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) if (f.isFile()) size += f.length();
            Matcher mod = Pattern.compile("(?s)<id>multisector</id>.*?<versionInfo[^>]*>.*?<string>([^<]*)</string>")
                    .matcher(xml);
            return new SaveInfo(dir, orDefault(tag(xml, "characterName"), dir.getName()),
                    localTime(tag(xml, "saveDate")), orDefault(tag(xml, "gameVersion"), "?"), size,
                    mod.find() ? mod.group(1).trim() : "");
        } catch (IOException e) {
            return null;
        }
    }

    /** "2026-11-21 16:32:26.559 UTC" -> "2026-11-22 00:32" in this PC's time zone. */
    static String localTime(String utc) {
        if (utc == null) return "?";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.ROOT);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(in.parse(utc.trim()));
        } catch (java.text.ParseException e) {
            return utc;
        }
    }

    private static long descriptorTime(File dir) {
        return new File(dir, "descriptor.xml").lastModified();
    }

    /** Text of the first &lt;name ...&gt;text&lt;/name&gt;, or null. */
    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + "(?:\\s[^>]*)?>([^<]*)</" + name + ">").matcher(xml);
        if (!m.find()) return null;
        return m.group(1).trim().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /**
     * The world's seed ("MN-...") from campaign.xml. It's the same for the host's
     * save and every copy of it, so the mod uses it to recognise the shared world.
     * Read line by line: it sits near the end of a file that can be 100 MB.
     */
    static String readSeed(File saveDir) {
        File f = new File(saveDir, "campaign.xml");
        Pattern p = Pattern.compile("<seedString>([^<]+)</seedString>");
        try (java.io.BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.contains("<seedString>")) continue;
                Matcher m = p.matcher(line);
                if (m.find()) return m.group(1).trim();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /**
     * Changes how a downloaded save appears in the game's Load list: your
     * character's name and portrait instead of the host's, so it's easy to
     * tell apart from the host's own save.
     */
    static void relabel(File saveDir, String label, String portrait) throws IOException {
        File desc = new File(saveDir, "descriptor.xml");
        String xml = new String(Files.readAllBytes(desc.toPath()), StandardCharsets.UTF_8);
        xml = replaceTag(xml, "characterName", escapeXml(label));
        if (portrait != null && !portrait.isEmpty()) xml = replaceTag(xml, "portraitName", escapeXml(portrait));
        xml = replaceTag(xml, "characterLevel", "1");
        Files.write(desc.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }

    private static String replaceTag(String xml, String name, String value) {
        return xml.replaceFirst("(<" + name + "(?:\\s[^>]*)?>)[^<]*(</" + name + ">)",
                "$1" + Matcher.quoteReplacement(value) + "$2");
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Rewrites the descriptor's save date to now, so the downloaded save is
     * the newest one and the main menu's Continue button loads it.
     */
    static void touchSaveDate(File saveDir) throws IOException {
        File desc = new File(saveDir, "descriptor.xml");
        String xml = new String(Files.readAllBytes(desc.toPath()), StandardCharsets.UTF_8);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String now = f.format(new Date());
        xml = xml.replaceFirst("(<saveDate(?:\\s[^>]*)?>)[^<]*(</saveDate>)", "$1" + Matcher.quoteReplacement(now) + "$2");
        Files.write(desc.toPath(), xml.getBytes(StandardCharsets.UTF_8));
        long t = System.currentTimeMillis();
        File[] files = saveDir.listFiles();
        if (files != null) for (File file : files) file.setLastModified(t);
        saveDir.setLastModified(t);
    }

    // ------------------------------------------------------------------- mods

    /** Mod ids from mods/enabled_mods.json. */
    List<String> enabledMods() {
        List<String> out = new ArrayList<String>();
        File f = new File(mods, "enabled_mods.json");
        if (!f.isFile()) return out;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Matcher arr = Pattern.compile("\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
            if (arr.find()) {
                Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(arr.group(1));
                while (s.find()) out.add(s.group(1));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Installed mods: id -> name, from each mods/&lt;folder&gt;/mod_info.json. */
    Map<String, String> installedMods() {
        Map<String, String> out = new java.util.TreeMap<String, String>();
        File[] dirs = mods.listFiles();
        if (dirs == null) return out;
        Pattern id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Pattern name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
        for (File d : dirs) {
            File info = new File(d, "mod_info.json");
            if (!d.isDirectory() || !info.isFile()) continue;
            try {
                String text = new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8);
                Matcher m = id.matcher(text);
                if (!m.find()) continue;
                Matcher n = name.matcher(text);
                out.put(m.group(1), n.find() ? n.group(1) : m.group(1));
            } catch (IOException ignored) {
                // unreadable: as good as not installed
            }
        }
        return out;
    }

    /**
     * Installed mods' versions: id -> version, from each mod_info.json. The
     * version is either a string ("0.12.3") or {major, minor, patch}.
     */
    Map<String, String> modVersions() {
        Map<String, String> out = new java.util.TreeMap<String, String>();
        File[] dirs = mods.listFiles();
        if (dirs == null) return out;
        Pattern id = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        for (File d : dirs) {
            File info = new File(d, "mod_info.json");
            if (!d.isDirectory() || !info.isFile()) continue;
            try {
                String text = new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8);
                Matcher m = id.matcher(text);
                if (m.find()) out.put(m.group(1), versionOf(text));
            } catch (IOException ignored) {
                // unreadable: as good as not installed
            }
        }
        return out;
    }

    static String versionOf(String modInfo) {
        Matcher s = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]*)\"").matcher(modInfo);
        if (s.find()) return s.group(1).trim();
        Matcher o = Pattern.compile("\"version\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL).matcher(modInfo);
        if (!o.find()) return "?";
        String body = o.group(1);
        StringBuilder b = new StringBuilder();
        for (String part : new String[]{"major", "minor", "patch"}) {
            Matcher p = Pattern.compile("\"" + part + "\"\\s*:\\s*\"?([^\",}\\s]*)").matcher(body);
            if (!p.find()) continue;
            if (b.length() > 0) b.append('.');
            b.append(p.group(1));
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    /** Where the player's own mod list is kept while the launcher has set the host's. */
    File enabledModsBackup() {
        return new File(mods, "enabled_mods.json.multisector-backup");
    }

    /**
     * Sets the game's enabled mods (Starsector's own mods/enabled_mods.json).
     * The first time, the player's own list is kept aside so it can be put back.
     */
    void writeEnabledMods(List<String> ids) throws IOException {
        File f = new File(mods, "enabled_mods.json");
        File backup = enabledModsBackup();
        if (f.isFile() && !backup.exists()) Files.copy(f.toPath(), backup.toPath());
        StringBuilder b = new StringBuilder("{\"enabledMods\": [\n");
        for (int i = 0; i < ids.size(); i++) {
            b.append("  \"").append(ids.get(i).replace("\"", "")).append('"').append(i < ids.size() - 1 ? "," : "").append('\n');
        }
        b.append("]}\n");
        Files.write(f.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Puts the player's own mod list back. False if there was nothing kept aside. */
    boolean restoreEnabledMods() throws IOException {
        File backup = enabledModsBackup();
        if (!backup.isFile()) return false;
        Files.copy(backup.toPath(), new File(mods, "enabled_mods.json").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(backup.toPath());
        return true;
    }

    /** Deletes a common file (the launcher runs outside the game's sandbox). */
    void deleteCommon(String name) {
        File f = new File(common, name + ".data");
        if (f.isFile() && !f.delete()) f.deleteOnExit();
    }

    // ---------------------------------------------------------- common files

    /** Writes saves/common/&lt;name&gt;.data, which the mod reads with readTextFileFromCommon(name). */
    void writeCommon(String name, String text) throws IOException {
        if (!common.isDirectory() && !common.mkdirs()) throw new IOException("Can't create " + common);
        Files.write(new File(common, name + ".data").toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    String readCommon(String name) {
        File f = new File(common, name + ".data");
        try {
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- launch

    boolean isGameRunning() {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq java.exe", "/V", "/FO", "CSV").start();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            return out.toLowerCase(Locale.ROOT).contains("starsector");
        } catch (IOException e) {
            return false;
        }
    }

    void launchGame() throws IOException {
        File exe = new File(root, "starsector.exe");
        if (!exe.isFile()) throw new IOException("Can't find " + exe);
        new ProcessBuilder(exe.getAbsolutePath()).directory(root).start();
    }

    // ---------------------------------------------------------------- utils

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String orDefault(String s, String d) {
        return s == null || s.isEmpty() ? d : s;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
