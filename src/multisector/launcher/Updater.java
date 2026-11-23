package multisector.launcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs another MultiSector release from GitHub, so players don't have to
 * download, delete and unzip by hand for every test build.
 *
 * <ol>
 * <li>Downloads {@code MultiSector-<version>.zip} from the release tagged
 *     {@code v<version>} (or the newest release).</li>
 * <li>Unpacks it next to the mod ({@code mods/MultiSector.update}) and checks
 *     it's the version asked for.</li>
 * <li>Writes a small script that waits for this launcher to close (the
 *     launcher runs from the mod's own jar, which can't be replaced while
 *     it's open), mirrors the new files over the mod folder, keeping your
 *     {@code multisector.json} settings, and opens the new launcher.</li>
 * </ol>
 * Only ever run when the player clicks the button, and never while
 * Starsector is running.
 */
final class Updater {

    static final String REPO = "bluenerd8/MultiSector";
    private static final String UA = "MultiSector-launcher";
    private static final int MAX_ZIP = 64 * 1024 * 1024;

    interface Progress {
        void update(String text);
    }

    private Updater() {
    }

    /** The newest release's version ("0.1.1"), pre-releases included. */
    static String newestVersion() throws IOException {
        byte[] body = get("https://api.github.com/repos/" + REPO + "/releases?per_page=1", 1024 * 1024, null);
        Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(new String(body, StandardCharsets.UTF_8));
        if (!m.find()) throw new IOException("GitHub didn't list any MultiSector releases.");
        return m.group(1);
    }

    /**
     * Downloads and stages that version, writes the swap script, and returns
     * it. The caller starts it and closes the launcher.
     */
    static File prepare(GameInstall game, String version, Progress progress) throws IOException {
        if (!version.matches("[0-9A-Za-z.\\-]{1,32}")) throw new IOException("That isn't a version: " + version);
        String url = "https://github.com/" + REPO + "/releases/download/v" + version + "/MultiSector-" + version + ".zip";
        progress.update("Downloading MultiSector " + version + "...");
        byte[] zip = get(url, MAX_ZIP, progress);
        File stage = new File(game.mods, "MultiSector.update");
        if (stage.exists()) deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("Couldn't make " + stage);
        progress.update("Unpacking...");
        unzip(zip, stage);
        File modDir = new File(stage, "MultiSector");
        File info = new File(modDir, "mod_info.json");
        if (!info.isFile()) throw new IOException("That download isn't a MultiSector release (no mod_info.json).");
        String text = new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8);
        Matcher v = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
        if (!v.find() || !v.group(1).equals(version)) {
            throw new IOException("The download says it's version " + (v.find(0) ? v.group(1) : "?") + ", not " + version + ".");
        }
        return writeScript(game, modDir);
    }

    private static File writeScript(GameInstall game, File newMod) throws IOException {
        File target = new File(game.mods, "MultiSector");
        long pid = ProcessHandle.current().pid();
        String nl = "\r\n";
        String s = "@echo off" + nl
                + "title MultiSector update" + nl
                + "echo Updating MultiSector. This window closes by itself." + nl
                + ":wait" + nl
                + "tasklist /FI \"PID eq " + pid + "\" 2>nul | find \"" + pid + "\" >nul" + nl
                + "if not errorlevel 1 (" + nl
                + "  timeout /t 1 /nobreak >nul" + nl
                + "  goto wait" + nl
                + ")" + nl
                + "robocopy \"" + newMod.getAbsolutePath() + "\" \"" + target.getAbsolutePath()
                + "\" /MIR /XF multisector.json /NFL /NDL /NJH /NJS /NP >nul" + nl
                + "if errorlevel 8 (" + nl
                + "  echo The update couldn't be copied. Close Starsector and anything using the mod folder, then try again." + nl
                + "  pause" + nl
                + "  exit /b 1" + nl
                + ")" + nl
                + "rmdir /s /q \"" + newMod.getParentFile().getAbsolutePath() + "\"" + nl
                + "start \"\" \"" + new File(target, "MultiSector Launcher.cmd").getAbsolutePath() + "\"" + nl
                + "del \"%~f0\"" + nl;
        File script = new File(game.mods, "MultiSector-update.cmd");
        Files.write(script.toPath(), s.getBytes(StandardCharsets.US_ASCII));
        return script;
    }

    /** Runs the swap script in its own window; the caller exits right after. */
    static void start(File script) throws IOException {
        new ProcessBuilder("cmd", "/c", "start", "\"MultiSector update\"", "/min", script.getAbsolutePath())
                .directory(script.getParentFile()).start();
    }

    private static byte[] get(String url, int max, Progress progress) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", UA);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                if (next == null) throw new IOException("GitHub sent a redirect without a location.");
                url = next;
                continue;
            }
            if (code == 404) throw new IOException("There's no such release on GitHub (" + url + ").");
            if (code != 200) throw new IOException("GitHub answered " + code + " for " + url);
            long total = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                long done = 0, shown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done > max) throw new IOException("That download is too big to be MultiSector.");
                    if (progress != null && total > 0 && done - shown > 256 * 1024) {
                        shown = done;
                        progress.update(String.format(java.util.Locale.ROOT, "Downloading... %d%%", done * 100 / total));
                    }
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        }
        throw new IOException("Too many redirects from GitHub.");
    }

    /** Unpacks with paths, never outside the folder. */
    private static void unzip(byte[] data, File dir) throws IOException {
        String root = dir.getCanonicalPath() + File.separator;
        ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data));
        ZipEntry e;
        byte[] buf = new byte[64 * 1024];
        while ((e = zip.getNextEntry()) != null) {
            File f = new File(dir, e.getName());
            if (!f.getCanonicalPath().startsWith(root)) throw new IOException("Bad path in the download: " + e.getName());
            if (e.isDirectory()) {
                f.mkdirs();
                continue;
            }
            f.getParentFile().mkdirs();
            OutputStream out = Files.newOutputStream(f.toPath());
            try {
                int n;
                while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        }
    }

    private static void deleteTree(File dir) throws IOException {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!dir.delete() && dir.exists()) throw new IOException("Couldn't delete " + dir);
    }
}
