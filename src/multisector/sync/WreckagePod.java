package multisector.sync;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignEngineLayers;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.campaign.BaseCustomEntityPlugin;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;

/**
 * How a battle's wreckage looks in space: our own salvage pod (an armoured
 * hexagon with a hazard band and a glowing core) slowly turning, with an
 * amber glow pulsing around it, so it's never mistaken for the game's own
 * cargo pods or derelicts. It drifts away after its days are up, once
 * nobody's looking, as a derelict does.
 *
 * Saved with the pod (the game keeps an entity's plugin in the save).
 */
public class WreckagePod extends BaseCustomEntityPlugin {

    static final String BODY = "graphics/multisector/salvage_pod.png";
    static final String GLOW = "graphics/multisector/salvage_pod_glow.png";
    private static final Color AMBER = new Color(255, 175, 50);

    private float days = Wreckage.DAYS;
    private float elapsed;
    private boolean expiring;
    private float time;
    private transient SpriteAPI body, glow;

    @Override
    public void init(SectorEntityToken entity, Object params) {
        super.init(entity, params);
        if (params instanceof Float) days = (Float) params;
        entity.setFacing((float) Math.random() * 360f);
        readResolve();
    }

    Object readResolve() {
        try {
            body = Global.getSettings().getSprite(BODY);
            glow = Global.getSettings().getSprite(GLOW);
        } catch (RuntimeException e) {
            body = null;
            glow = null;
        }
        return this;
    }

    float daysLeft() {
        return Math.max(1f, days - elapsed);
    }

    @Override
    public void advance(float amount) {
        time += amount;
        if (entity.isInCurrentLocation()) entity.setFacing(Misc.normalizeAngle(entity.getFacing() + amount * 4f));
        elapsed += Global.getSector().getClock().convertToDays(amount);
        if (elapsed > days && !expiring && !(entity.isInCurrentLocation() && entity.isVisibleToPlayerFleet())) {
            Misc.fadeAndExpire(entity, 1f);
            expiring = true;
        }
    }

    @Override
    public float getRenderRange() {
        return entity.getRadius() + 200f;
    }

    @Override
    public void render(CampaignEngineLayers layer, ViewportAPI viewport) {
        float alpha = viewport.getAlphaMult() * entity.getSensorFaderBrightness() * entity.getSensorContactFaderBrightness();
        if (alpha <= 0f) return;
        if (body == null) readResolve();
        if (body == null) return;
        Vector2f loc = entity.getLocation();
        float pulse = 0.6f + 0.4f * (float) Math.sin(time * 2.4f);
        if (glow != null) {
            glow.setColor(AMBER);
            glow.setAdditiveBlend();
            glow.setSize(170f, 170f);
            glow.setAlphaMult(alpha * 0.55f * pulse);
            glow.renderAtCenter(loc.x, loc.y);
        }
        body.setNormalBlend();
        body.setSize(60f, 60f);
        body.setAngle(entity.getFacing());
        body.setAlphaMult(alpha);
        body.renderAtCenter(loc.x, loc.y);
        if (glow != null) { // the core's own glow, teal
            glow.setColor(new Color(90, 255, 235));
            glow.setAdditiveBlend();
            glow.setSize(36f, 36f);
            glow.setAlphaMult(alpha * (0.5f + 0.5f * pulse));
            glow.renderAtCenter(loc.x, loc.y);
        }
    }
}
