package multisector;

import com.fs.starfarer.api.BaseModPlugin;
import multisector.probe.SandboxProbe;

/**
 * Mod entry point. For now it only runs the sandbox probe, which logs which
 * Java APIs the game's ScriptClassLoader lets mod code use. The results decide
 * how the network layer gets built.
 */
public class MultiSectorModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        SandboxProbe.runAll();
    }
}
