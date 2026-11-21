package multisector.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.abilities.BaseAbilityPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import multisector.net.Session;

/**
 * The "Multiplayer" button on the ability bar. Clicking it opens the
 * Multiplayer window. It lights up while hosting or connected.
 */
public class MultiplayerAbility extends BaseAbilityPlugin {

    public static final String ID = "multisector_multiplayer";

    @Override
    public void pressButton() {
        if (!getFleet().isPlayerFleet()) return;
        try {
            Global.getSoundPlayer().playUISound("ui_button_pressed", 1f, 1f);
        } catch (Exception ignored) {
        }
        MultiplayerDialog.open();
    }

    @Override
    public boolean hasCustomButtonPressSounds() {
        return true;
    }

    @Override
    public boolean isUsable() {
        return true;
    }

    /** Shows the "on" highlight while a session is running. */
    @Override
    public boolean isActive() {
        return Session.get().isActive();
    }

    @Override
    public boolean showActiveIndicator() {
        return isActive();
    }

    @Override
    public float getCooldownFraction() {
        return 1f;
    }

    @Override
    public void setCooldownLeft(float days) {
    }

    @Override
    public float getCooldownLeft() {
        return 0f;
    }

    @Override
    public boolean isTooltipExpandable() {
        return false;
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        tooltip.addTitle("Multiplayer");
        tooltip.addPara("Host or join a MultiSector multiplayer game.", 10f);
        Session s = Session.get();
        tooltip.addPara("Status: %s", 10f,
                s.isActive() ? Misc.getPositiveHighlightColor() : Misc.getGrayColor(), s.getStatus());
        tooltip.addPara("Shortcut: %s", 10f, Misc.getHighlightColor(), MultiSectorConfig.getHotkeyName());
    }
}
