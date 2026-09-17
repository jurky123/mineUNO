package com.mineuno;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 当前回合标识：悬浮在玩家头顶，负责移动、文案与跳过动画。 */
public class TurnMarker {

    private final MineUnoPlugin plugin;
    private final World world;
    private TextDisplay display;

    public TurnMarker(MineUnoPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
    }

    private static Transformation scale(float value) {
        return new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(value, value, value), new Quaternionf());
    }

    private TextDisplay entity(Location at) {
        if (display == null || !display.isValid()) {
            display = world.spawn(at, TextDisplay.class, t -> {
                t.text(MineUnoPlugin.mm("<gold>▶"));
                t.setBillboard(Display.Billboard.CENTER);
                t.setSeeThrough(true);
                t.setDefaultBackground(false);
                t.setBackgroundColor(org.bukkit.Color.fromARGB(0x55000000));
                t.setShadowed(true);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setTransformation(scale(0.01f));
                t.setViewRange(1.5f);
            });
        }
        return display;
    }

    /** 飞到目标位置显示文字（带弹跳）。 */
    public void show(Location at, String mini, int flightTicks) {
        TextDisplay t = entity(at);
        t.text(MineUnoPlugin.mm(mini));
        t.setInterpolationDuration(flightTicks);
        t.setTeleportDuration(flightTicks);
        t.setTransformation(scale(0.05f));
        t.teleport(at.clone());
        schedule(flightTicks, () -> {
            if (t.isValid()) {
                t.setInterpolationDuration(4);
                t.setTransformation(scale(1.15f));
            }
        });
        schedule(flightTicks + 5, () -> {
            if (t.isValid()) t.setTransformation(scale(1.0f));
        });
    }

    public void hide() {
        if (display != null && display.isValid()) display.setTransformation(scale(0.01f));
    }

    public void remove() {
        if (display != null && display.isValid()) display.remove();
        display = null;
    }

    private void schedule(int delay, Runnable run) {
        plugin.getServer().getScheduler().runTaskLater(plugin, run, delay);
    }
}
