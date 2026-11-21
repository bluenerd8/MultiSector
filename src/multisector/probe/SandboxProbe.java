package multisector.probe;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * Tries each Java API the network layer might need and logs PASS or BLOCKED.
 *
 * Starsector loads mod code through com.fs.starfarer.loading.scripts.ScriptClassLoader,
 * which refuses to load some JDK packages (java.lang.reflect, most of java.io,
 * java.nio.file, ...). The refusal happens when a class referencing them is
 * first resolved, so every probe lives in its own class and is created inside
 * a try/catch. This class itself must only touch safe types.
 *
 * Output goes to starsector-core/starsector.log, lines tagged [MultiSector probe].
 */
public final class SandboxProbe {

    private static final String TAG = "[MultiSector probe] ";

    private SandboxProbe() {
    }

    public static void runAll() {
        Logger log = Global.getLogger(SandboxProbe.class);
        log.info(TAG + "starting sandbox probe");

        int passed = 0;
        int blocked = 0;
        StringBuilder blockedNames = new StringBuilder();

        for (int i = 0; i < Probes.COUNT; i++) {
            String name = Probes.name(i);
            try {
                String detail = Probes.run(i);
                log.info(TAG + "PASS    " + name + " -> " + detail);
                passed++;
            } catch (Throwable t) {
                log.info(TAG + "BLOCKED " + name + " -> " + t.getClass().getName() + ": " + t.getMessage());
                blocked++;
                if (blockedNames.length() > 0) blockedNames.append(", ");
                blockedNames.append(name);
            }
        }

        log.info(TAG + "done: " + passed + " passed, " + blocked + " blocked"
                + (blocked > 0 ? " (" + blockedNames + ")" : ""));
    }
}
