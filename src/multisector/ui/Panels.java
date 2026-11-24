package multisector.ui;

import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.ScrollPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;

/** Small things about the game's custom panels. */
final class Panels {

    private Panels() {
    }

    /**
     * Takes a built section off its panel. A section made with a scroll bar
     * sits inside a scroll panel the game wraps around it: that wrapper is
     * what the panel holds, so removing the section alone leaves it showing,
     * and the next build draws over it.
     */
    static void remove(CustomPanelAPI panel, TooltipMakerAPI section) {
        if (panel == null || section == null) return;
        try {
            ScrollPanelAPI wrapper = section.getExternalScroller();
            if (wrapper != null) panel.removeComponent(wrapper);
        } catch (RuntimeException ignored) {
            // no wrapper
        }
        try {
            panel.removeComponent(section);
        } catch (RuntimeException ignored) {
            // already off
        }
    }
}
