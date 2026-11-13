package net.nestworld.inspector.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.nestworld.inspector.Snapshot;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client half: keeps the latest snapshot, draws outlines (one line batch) and the HUD. Dist.CLIENT only. */
@OnlyIn(Dist.CLIENT)
public final class ClientInspector {
    private static final KeyMapping TOGGLE = new KeyMapping("key.nestworld_inspector.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.nestworld_inspector");
    private static final long STALE_MS = 3000;

    private static volatile Snapshot snapshot;
    private static volatile long receivedAt;
    private static Map<Long, Snapshot.Be> beByPos = Map.of();
    private static boolean enabled = true;

    private ClientInspector() {}

    public static void init(IEventBus modBus) {
        modBus.addListener(ClientInspector::onKeys);
        modBus.addListener(ClientInspector::onOverlays);
        MinecraftForge.EVENT_BUS.addListener(ClientInspector::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(ClientInspector::onRender);
        MinecraftForge.EVENT_BUS.addListener(ClientInspector::onLogout);
    }

    /** Main client thread (consumerMainThread). {@code s == null} clears. */
    public static void accept(Snapshot s) {
        Map<Long, Snapshot.Be> m = new HashMap<>();
        if (s != null) for (Snapshot.Be b : s.blockEntities) m.put(b.pos(), b);
        beByPos = m;
        snapshot = s;
        receivedAt = System.currentTimeMillis();
    }

    private static void onKeys(RegisterKeyMappingsEvent e) {
        e.register(TOGGLE);
    }

    private static void onOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("nwinspect_hud", ClientInspector::renderHud);
    }

    private static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        while (TOGGLE.consumeClick()) enabled = !enabled;
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        accept(null);
    }

    // ---- colors ----

    static final float[] MAIN = {1f, 0.15f, 0.15f}, NONE = {0.55f, 0.55f, 0.55f}, GROUP = {1f, 1f, 0.2f},
            UNEXPLAINED = {1f, 0.2f, 1f};

    /** Stable per-region color (golden-ratio hue walk), kept away from MAIN red. */
    static float[] regionColor(int id) {
        if (id == Snapshot.NONE) return NONE;
        float hue = (float) ((0.08 + (id & 0x7fffffff) * 0.618034) % 1.0);
        hue = 0.08f + hue * 0.84f; // hues 0.08..0.92: never red
        int rgb = net.minecraft.util.Mth.hsvToRgb(hue, 0.8f, 1f);
        return new float[]{((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f};
    }

    static float[] domainColor(Snapshot.Be b) {
        if (b.reason() == Snapshot.R_UNEXPLAINED) return UNEXPLAINED;
        return b.domain() == Snapshot.NONE ? MAIN : regionColor(b.domain());
    }

    static int argb(float[] c) {
        return 0xFF000000 | ((int) (c[0] * 255) << 16) | ((int) (c[1] * 255) << 8) | (int) (c[2] * 255);
    }

    private static Snapshot current(Minecraft mc) {
        Snapshot s = snapshot;
        if (s == null || mc.level == null) return null;
        if (!s.dimension.equals(mc.level.dimension().location().toString())) return null;
        return s;
    }

    // ---- world rendering ----

    private static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !enabled) return;
        Minecraft mc = Minecraft.getInstance();
        Snapshot s = current(mc);
        if (s == null || mc.player == null) return;
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        double y0 = Math.floor(mc.player.getY()) - 8, y1 = y0 + 24;
        int r = s.radius, side = 2 * r + 1;
        for (int i = 0; i < s.chunks.size() && i < side * side; i++) {
            Snapshot.Chunk ch = s.chunks.get(i);
            if (!ch.loaded() && ch.regionId() == Snapshot.NONE) continue;
            int cx = s.centerCX + i / side - r, cz = s.centerCZ + i % side - r;
            double x0 = (cx << 4) + 0.06, z0 = (cz << 4) + 0.06, x1 = (cx << 4) + 15.94, z1 = (cz << 4) + 15.94;
            float[] c = regionColor(ch.regionId());
            LevelRenderer.renderLineBox(ps, vc, new AABB(x0, y0, z0, x1, y1, z1), c[0], c[1], c[2], ch.band() ? 0.45f : 0.9f);
            if (ch.band()) { // border band: block-entity work here runs on MAIN -> red X on the floor plane
                double fy = Math.floor(mc.player.getY()) + 0.02;
                line(ps, vc, x0, fy, z0, x1, fy, z1, MAIN);
                line(ps, vc, x0, fy, z1, x1, fy, z0, MAIN);
            }
        }
        double maxDistSq = (double) (r * 16 + 16) * (r * 16 + 16);
        for (Snapshot.Be b : s.blockEntities) {
            BlockPos p = BlockPos.of(b.pos());
            if (p.distToCenterSqr(cam) > maxDistSq) continue;
            float[] c = domainColor(b);
            LevelRenderer.renderLineBox(ps, vc, new AABB(p).inflate(0.004), c[0], c[1], c[2], 1f);
            if (b.groupSize() > 0) {
                LevelRenderer.renderLineBox(ps, vc, new AABB(p).inflate(0.07), GROUP[0], GROUP[1], GROUP[2], 0.9f);
            }
        }
        buffers.endBatch(RenderType.lines());
        ps.popPose();
    }

    private static void line(PoseStack ps, VertexConsumer vc, double ax, double ay, double az, double bx, double by, double bz, float[] c) {
        Matrix4f m = ps.last().pose();
        Matrix3f n = ps.last().normal();
        float dx = (float) (bx - ax), dy = (float) (by - ay), dz = (float) (bz - az);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len == 0) return;
        dx /= len; dy /= len; dz /= len;
        vc.vertex(m, (float) ax, (float) ay, (float) az).color(c[0], c[1], c[2], 1f).normal(n, dx, dy, dz).endVertex();
        vc.vertex(m, (float) bx, (float) by, (float) bz).color(c[0], c[1], c[2], 1f).normal(n, dx, dy, dz).endVertex();
    }

    // ---- HUD ----

    private static void renderHud(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || mc.options.hideGui || mc.options.renderDebug || mc.player == null) return;
        Snapshot s = snapshot;
        if (s == null) return; // nothing enabled / server lacks the inspector: draw nothing
        Font font = mc.font;
        List<Object[]> lines = new ArrayList<>(); // {text, argb}
        boolean stale = System.currentTimeMillis() - receivedAt > STALE_MS;
        Snapshot cur = current(mc);
        lines.add(new Object[]{"NestWorld Inspector" + (stale ? " (stale)" : "") + String.format("  MSPT %.1f", s.mspt), 0xFFFFFFFF});
        if (cur == null) {
            lines.add(new Object[]{"other dimension: " + s.dimension, 0xFFAAAAAA});
        } else if (!cur.managed) {
            lines.add(new Object[]{"dimension not region-managed: everything on MAIN", argb(MAIN)});
        } else {
            int pcx = mc.player.chunkPosition().x, pcz = mc.player.chunkPosition().z;
            Snapshot.Chunk ch = cur.chunkAt(pcx, pcz);
            if (ch != null) {
                String rn = ch.regionId() == Snapshot.NONE ? "no region (MAIN)" : "REGION#" + ch.regionId();
                Snapshot.Region reg = ch.regionId() == Snapshot.NONE ? null : cur.region(ch.regionId());
                String ms = reg == null ? "" : String.format("  %.2f ms/tick", reg.avgTickMs());
                lines.add(new Object[]{"chunk " + pcx + "," + pcz + ": " + rn + ms, argb(regionColor(ch.regionId()))});
                lines.add(new Object[]{ch.regionId() == Snapshot.NONE ? "  block entities here: MAIN"
                        : ch.band() ? "  border band: block entities here run on MAIN" : "  interior: block entities run on REGION#" + ch.regionId(),
                        ch.band() || ch.regionId() == Snapshot.NONE ? argb(MAIN) : argb(regionColor(ch.regionId()))});
            }
            lines.add(new Object[]{"regions in view: " + cur.regions.size() + ", block entities: " + cur.blockEntities.size()
                    + (cur.truncated ? " (truncated)" : ""), 0xFFCCCCCC});
            HitResult hit = mc.hitResult;
            if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
                Snapshot.Be b = beByPos.get(bh.getBlockPos().asLong());
                if (b != null) {
                    lines.add(new Object[]{"target: " + b.type(), 0xFFFFFFFF});
                    lines.add(new Object[]{"  domain " + (b.domain() == Snapshot.NONE ? "MAIN" : "REGION#" + b.domain())
                            + " <- " + Snapshot.reasonName(b.reason()), argb(domainColor(b))});
                    if (b.groupSize() > 0) lines.add(new Object[]{"  BE group: " + b.groupSize() + " members", argb(GROUP)});
                }
            }
        }
        lines.add(new Object[]{"red=MAIN  gray=no region  color=REGION#id  red X=border band  yellow box=BE group  magenta=Core differs", 0xFF999999});
        int y = 4;
        for (Object[] l : lines) {
            String t = (String) l[0];
            g.fill(2, y - 1, 6 + font.width(t), y + 9, 0x80000000);
            g.drawString(font, t, 4, y, (int) l[1], false);
            y += 10;
        }
    }
}
