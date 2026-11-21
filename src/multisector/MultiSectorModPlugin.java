package multisector;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import multisector.net.AddressFinder;
import multisector.probe.SandboxProbe;
import org.apache.log4j.Logger;

import java.util.List;

/**
 * Mod entry point. For now it runs the sandbox probe and logs this PC's
 * network addresses (so a Hamachi host can find theirs in the log).
 */
public class MultiSectorModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        SandboxProbe.runAll();
        logAddresses();
    }

    private static void logAddresses() {
        Logger log = Global.getLogger(MultiSectorModPlugin.class);
        try {
            List<AddressFinder.Candidate> all = AddressFinder.find();
            for (AddressFinder.Candidate c : all) {
                log.info("[MultiSector net] address " + c);
            }
            AddressFinder.Candidate hamachi = AddressFinder.first(all, AddressFinder.Kind.HAMACHI);
            log.info("[MultiSector net] Hamachi address: " + (hamachi != null ? hamachi.ip() : "none (Hamachi off or not installed)"));
        } catch (Throwable t) {
            // NetworkInterface may be blocked by the sandbox; the probe log shows which.
            log.info("[MultiSector net] could not list addresses: " + t);
        }
    }
}
