package dev.toaru.blackhole.client;

import dev.toaru.blackhole.BlackHoleMod;
import dev.toaru.blackhole.client.ClientFx.Effect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Enhanced black hole rendering with:
 * - Einstein ring (gravitational lensing of background)
 * - Corona/halo with chromatic aberration
 * - Relativistic jets (polar outflows)
 * - Magnetic field line visualization
 * - Doppler-shifted accretion disk (blue/red shift)
 * - Frame-dragging vortex lines
 * - Gravitational wave ripples
 * - Improved collapse: neutron star bounce → black hole formation
 * - Screen-space distortion when near
 */
@Mod.EventBusSubscriber(modid = BlackHoleMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FxRenderer {
    private FxRenderer() { }

    private static final double MAX_DISTANCE = 260.0;

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ClientFx.ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = mc.isPaused() ? 0f : event.getPartialTick();
        long now = mc.level.getGameTime();
        Vec3 cam = event.getCamera().getPosition();
        Matrix4f pose = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Pass 1: Dark/opaque geometry (event horizon, shadow)
        Geo dark = new Geo(buffers.getBuffer(BhRenderTypes.DARK), pose, cam);
        for (Effect fx : ClientFx.ACTIVE) {
            if (fx.pos.distanceTo(cam) <= MAX_DISTANCE) drawDark(fx, fx.age(now, partial), dark, cam);
        }
        buffers.endBatch(BhRenderTypes.DARK);

        // Pass 2: Distortion lens (renders after translucent for lensing effect)
        Geo distortion = new Geo(buffers.getBuffer(BhRenderTypes.DISTORTION), pose, cam);
        for (Effect fx : ClientFx.ACTIVE) {
            if (fx.pos.distanceTo(cam) <= MAX_DISTANCE) drawDistortion(fx, fx.age(now, partial), distortion, cam);
        }
        buffers.endBatch(BhRenderTypes.DISTORTION);

        // Pass 3: Additive glow (rings, corona, jets, disk)
        Geo glow = new Geo(buffers.getBuffer(RenderType.lightning()), pose, cam);
        for (Effect fx : ClientFx.ACTIVE) {
            if (fx.pos.distanceTo(cam) <= MAX_DISTANCE) drawGlow(fx, fx.age(now, partial), glow, cam);
        }
        buffers.endBatch(RenderType.lightning());

        // Pass 4: Magnetic field lines (translucent)
        Geo field = new Geo(buffers.getBuffer(BhRenderTypes.FIELD), pose, cam);
        for (Effect fx : ClientFx.ACTIVE) {
            if (fx.pos.distanceTo(cam) <= MAX_DISTANCE) drawFieldLines(fx, fx.age(now, partial), field, cam);
        }
        buffers.endBatch(BhRenderTypes.FIELD);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double smoothstep(double t) { return t * t * (3.0 - 2.0 * t); }
    private static double smootherstep(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double mix(double a, double b, double t) { return a + (b - a) * t; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static int segments(Effect fx, Vec3 cam) {
        double dist = fx.pos.distanceTo(cam);
        return dist < 50 ? 64 : (dist < 120 ? 40 : 28);
    }

    /** Current event-horizon radius: grows during formation, breathes during active. */
    private static double horizonRadius(Effect fx, double age) {
        double form = clamp01(age / Math.max(1.0, fx.aux.z));
        double ease = smoothstep(form);
        double breathe = 0.95 + 0.05 * Math.sin(age * 0.08 + Math.sin(age * 0.03) * 0.5);
        return fx.aux.y * ease * breathe;
    }

    /** Photon sphere radius (1.5x horizon for Schwarzschild). */
    private static double photonSphereRadius(Effect fx, double age) {
        return horizonRadius(fx, age) * 1.5;
    }

    private static void drawDark(Effect fx, double age, Geo g, Vec3 cam) {
        int seg = segments(fx, cam);
        Vec3 c = fx.pos;
        double rh = horizonRadius(fx, age);
        double rph = photonSphereRadius(fx, age);

        if (rh < 0.02) return;

        switch (fx.type) {
            case BLACK_HOLE -> {
                // Event horizon - perfectly black, slightly larger than photon sphere for safety
                g.color(0f, 0f, 0f, 1.0f).sphere(c, rh, 16, seg);

                // Photon sphere - thin shell where light orbits
                g.color(0f, 0f, 0f, 0.15f).sphere(c, rph, 12, seg);

                // Shadow/umbra - slightly larger than photon sphere
                g.color(0f, 0f, 0f, 0.08f).sphere(c, rph * 1.02, 8, seg);
            }
            case BLACK_HOLE_BLAST -> {
                double t = age / Math.max(1, fx.duration);
                if (t < 0.15) {
                    double s = 1.0 - t / 0.15;
                    double rhCollapse = fx.aux.y * s * s * s;
                    if (rhCollapse > 0.02) g.color(0f, 0f, 0f, 1.0f).sphere(c, rhCollapse, 16, seg);
                }
            }
        }
    }

    private static void drawDistortion(Effect fx, double age, Geo g, Vec3 cam) {
        int seg = segments(fx, cam);
        Vec3 c = fx.pos;
        double rh = horizonRadius(fx, age);
        double rph = photonSphereRadius(fx, age);

        if (rh < 0.1) return;

        double form = clamp01(age / Math.max(1.0, fx.aux.z));
        if (form < 1.0) return;

        // Einstein ring - gravitational lensing of background
        // Rendered as a series of concentric distorted shells
        double einsteinR = rph * 1.02;
        for (int i = 0; i < 3; i++) {
            double shellR = einsteinR * (1.0 + i * 0.008);
            double alpha = 0.04 * (1.0 - i * 0.25) * (0.8 + 0.2 * Math.sin(age * 0.12 + i * 2.0));
            // Chromatic aberration: slightly different radii for RGB
            g.color((float) (0.4 + 0.1 * i), (float) (0.5 + 0.1 * i), 1.0f, (float) alpha).sphere(c, shellR, 16, seg);
        }

        // Distortion field extending beyond photon sphere
        double distortMax = rph * 2.5;
        for (int i = 0; i < 5; i++) {
            double shellR = rph + (distortMax - rph) * i / 5.0;
            double wave = Math.sin(age * 0.06 + shellR * 0.5 + i * 1.2) * 0.015;
            double alpha = 0.03 * (1.0 - i * 0.15) * (1.0 + wave * 50);
            g.color(0.3f + i * 0.05f, 0.4f + i * 0.05f, 1.0f, (float) alpha).sphere(c, shellR, 12, seg);
        }
    }

    private static void drawGlow(Effect fx, double age, Geo g, Vec3 cam) {
        int seg = segments(fx, cam);
        switch (fx.type) {
            case BLACK_HOLE -> blackHoleGlow(fx, age, g, cam, seg);
            case BLACK_HOLE_BLAST -> blastGlow(fx, age, g, cam, seg);
        }
    }

    private static void blackHoleGlow(Effect fx, double age, Geo g, Vec3 cam, int seg) {
        Vec3 c = fx.pos;
        double rh = horizonRadius(fx, age);
        double rph = photonSphereRadius(fx, age);
        double pullR = fx.aux.x;
        double form = clamp01(age / Math.max(1.0, fx.aux.z));
        double ease = smoothstep(form);

        if (rh < 0.02) return;

        Vec3 toCam = cam.subtract(c);
        toCam = toCam.lengthSqr() < 1e-6 ? new Vec3(0, 1, 0) : toCam.normalize();

        // ===== FORMATION PHASE =====
        if (age < fx.aux.z) {
            drawFormation(fx, age, g, cam, seg, c, rh, rph, toCam, ease);
            return;
        }

        // ===== ACTIVE PHASE =====
        double activeStart = fx.aux.z;
        double activeEnd = activeStart + (fx.duration - activeStart) * 0.8;
        double activeAge = age - activeStart;
        double activeProgress = clamp01(activeAge / Math.max(1, activeEnd - activeStart));

        // --- Photon ring (Einstein ring) - always faces camera ---
        drawPhotonRing(g, c, toCam, rh, rph, seg, ease, activeProgress);

        // --- Corona / Halo ---
        drawCorona(g, c, toCam, rh, rph, seg, ease, activeProgress, activeAge);

        // --- Accretion Disk with Doppler shift ---
        drawAccretionDisk(fx, age, g, c, rh, rph, pullR, seg, ease, activeProgress, activeAge);

        // --- Relativistic Jets ---
        drawJets(fx, age, g, c, rh, seg, ease, activeProgress, activeAge);

        // --- Frame-dragging vortex lines ---
        drawFrameDragging(fx, age, g, c, rh, pullR, seg, activeAge);

        // --- Gravitational wave ripples ---
        drawGravitationalWaves(fx, age, g, c, pullR, seg, ease, activeAge);

        // --- Collapse phase ---
        if (age >= activeEnd) {
            drawCollapse(fx, age, g, c, cam, rh, rph, pullR, seg, ease, activeEnd, activeProgress);
        }
    }

    private static void drawFormation(Effect fx, double age, Geo g, Vec3 cam, int seg,
                                       Vec3 c, double rh, double rph, Vec3 toCam, double ease) {
        double formEase = ease;
        double coreR = 0.4 * formEase;
        Vec3 handPos = c.add(0, 1.5, 0);

        // Collapsing core
        g.color(0.1f, 0.15f, 0.3f, (float) (0.9 * formEase)).sphere(handPos, coreR * 1.2, 10, seg / 2);
        g.color(0.0f, 0.0f, 0.1f, 1.0f).sphere(handPos, coreR, 12, seg / 2);

        // Formation rings - multiple frequencies
        for (int i = 0; i < 4; i++) {
            double ringPhase = age * (0.02 + i * 0.008) + i * 1.5;
            double tilt = Math.sin(ringPhase * 0.4) * 0.4;
            Vec3 normal = new Vec3(Math.sin(tilt), Math.cos(tilt), 0).normalize();
            double ringR = coreR * (1.5 + i * 0.5);
            double thickness = coreR * (0.06 + i * 0.03);
            float alpha = (float) (0.5 * formEase * (1.0 - i * 0.18));
            g.color(0.3f + i * 0.1f, 0.4f + i * 0.1f, 1.0f, alpha).ring(handPos, normal, ringR, thickness, seg, ringPhase, true);
        }

        // Infalling matter during formation
        for (int arm = 0; arm < 3; arm++) {
            double base = arm * Math.PI * 2.0 / 3;
            Vec3 prev = null;
            for (int j = 0; j <= 12; j++) {
                double s = j / 12.0;
                double rad = coreR * 2.0 + (rph - coreR * 2.0) * Math.pow(s, 1.5);
                double ang = base + age * 0.06 + s * 3.0;
                double h = Math.sin(base * 2.0 + s * 4.0) * (1.0 - s) * rph * 0.15;
                Vec3 pt = handPos.add(Math.cos(ang) * rad, h, Math.sin(ang) * rad);
                if (prev != null) {
                    g.color(1f, (float) lerp(0.6, 0.9, s), (float) lerp(0.2, 0.8, s), (float) (0.4 * formEase * (0.3 + 0.7 * s)));
                    g.ribbon(prev, pt, 0.02 + 0.04 * s);
                }
                prev = pt;
            }
        }
    }

    private static void drawPhotonRing(Geo g, Vec3 c, Vec3 toCam, double rh, double rph, int seg,
                                        double ease, double activeProgress) {
        // Main Einstein ring - bright, thin, at photon sphere
        float ringAlpha = (float) (0.95 * ease * (1.0 - activeProgress * 0.15));
        g.color(1.0f, 0.98f, 0.85f, ringAlpha).ring(c, toCam, rph * 1.005, rph * 0.012, seg, 0, false);

        // Outer lensing halos
        g.color(1.0f, 0.75f, 0.35f, (float) (0.25 * ease * (1.0 - activeProgress * 0.2))).ring(c, toCam, rph * 1.15, rph * 0.08, seg, 0, false);
        g.color(0.6f, 0.85f, 1.0f, (float) (0.18 * ease * (1.0 - activeProgress * 0.2))).ring(c, toCam, rph * 1.8, rph * 0.025, seg, 0, false);
        g.color(0.3f, 0.6f, 1.0f, (float) (0.12 * ease * (1.0 - activeProgress * 0.3))).ring(c, toCam, rph * 2.5, rph * 0.02, seg, 0, false);

        // Photon sphere glow (thin shell at 1.5x horizon)
        g.color(1.0f, 0.95f, 0.8f, (float) (0.15 * ease)).sphere(c, rph, 20, seg / 2);
    }

    private static void drawCorona(Geo g, Vec3 c, Vec3 toCam, double rh, double rph, int seg,
                                    double ease, double activeProgress, double activeAge) {
        // Corona - hot plasma atmosphere around black hole
        // Multiple layers with different temperatures (colors)

        // Inner corona (hottest - white/blue)
        for (int i = 0; i < 4; i++) {
            double layerR = rph * (1.02 + i * 0.03);
            double thickness = rph * (0.015 + i * 0.01);
            double pulse = Math.sin(activeAge * 0.12 + i * 1.0) * 0.08 + 1.0;
            float alpha = (float) (0.22 * ease * (1.0 - i * 0.18) * pulse * (1.0 - activeProgress * 0.1));
            g.color(1.0f, (float) lerp(0.98, 0.8, i * 0.25), (float) lerp(0.9, 0.5, i * 0.25), alpha)
                    .ring(c, toCam, layerR, thickness, seg, 0, false);
        }

        // Outer corona (cooler - orange/red)
        for (int i = 0; i < 3; i++) {
            double layerR = rph * (1.15 + i * 0.08);
            double thickness = rph * (0.03 + i * 0.02);
            double pulse = Math.sin(activeAge * 0.07 + i * 1.5) * 0.12 + 1.0;
            float alpha = (float) (0.15 * ease * (1.0 - i * 0.2) * pulse * (1.0 - activeProgress * 0.15));
            g.color(1.0f, (float) lerp(0.7, 0.3, i * 0.33), (float) lerp(0.3, 0.05, i * 0.33), alpha)
                    .ring(c, toCam, layerR, thickness, seg, 0, false);
        }

        // X-ray corona (very hot, extends far)
        g.color(1.0f, 1.0f, 0.9f, (float) (0.08 * ease * (1.0 - activeProgress * 0.2)))
                .ring(c, toCam, rph * 3.0, rph * 0.15, seg, 0, false);
    }

    private static void drawAccretionDisk(Effect fx, double age, Geo g, Vec3 c, double rh, double rph,
                                           double pullR, int seg, double ease, double activeProgress, double activeAge) {
        // Accretion disk with relativistic Doppler shift
        // Blue-shifted (approaching) side vs red-shifted (receding) side
        Vec3 diskNormal = new Vec3(Math.sin(0.35) * Math.cos(0.6), Math.cos(0.35), Math.sin(0.35) * Math.sin(0.6)).normalize();
        Vec3 diskRight = new Vec3(0, 1, 0).cross(diskNormal).normalize(); // Approximate "approaching" direction

        int rings = seg >= 64 ? 24 : (seg >= 40 ? 16 : 10);

        for (int k = 0; k < rings; k++) {
            double u = rings > 1 ? k / (double) (rings - 1) : 0.5;
            double innerR = rph * (1.5 + 0.5 * u);
            double outerR = rph * (2.5 + 4.5 * u);

            // Temperature gradient: hotter inside (blue/white) → cooler outside (red)
            // But with Doppler: approaching side is bluer, receding side is redder

            // We'll draw the ring multiple times with slight rotation to simulate Doppler
            double baseOmega = 0.25 / Math.pow(1.0 + 2.5 * u, 1.5); // Keplerian

            for (int side = -1; side <= 1; side += 2) {
                // side = -1: receding (redshift), side = +1: approaching (blueshift)
                double dopplerShift = side * 0.15 * (1.0 - u); // Stronger shift at inner radii
                double omega = baseOmega * (1.0 + dopplerShift * 0.5);

                double rColor, gColor, bColor;
                if (side > 0) {
                    // Approaching - blueshifted
                    rColor = lerp(0.8, 0.3, u);
                    gColor = lerp(0.9, 0.6, u);
                    bColor = 1.0;
                } else {
                    // Receding - redshifted
                    rColor = 1.0;
                    gColor = lerp(0.5, 0.1, u);
                    bColor = lerp(0.3, 0.0, u);
                }

                double thickness = rh * (0.03 + 0.05 * u);
                double pulse = Math.sin(activeAge * omega * 0.5 + k * 0.5) * 0.05 + 1.0;
                float alpha = (float) ((0.7 - 0.45 * u) * ease * pulse * (1.0 - activeProgress * 0.1));

                g.color((float) rColor, (float) gColor, (float) bColor, alpha)
                        .ring(c, diskNormal, (innerR + outerR) * 0.5, thickness, seg, activeAge * omega + k * 0.8, true);
            }

            // Central gap glow (inner edge of disk)
            if (k < 4) {
                double gapR = rph * (1.5 + 0.5 * u);
                g.color(1.0f, 0.95f, 0.85f, (float) (0.2 * ease * (1.0 - u * 0.5)))
                        .ring(c, diskNormal, gapR, rh * 0.02, seg, activeAge * 0.1, false);
            }
        }

        // Hot spot (simulated orbiting hotspot)
        double hotspotAngle = activeAge * 0.08;
        double hotspotR = rph * 1.8;
        Vec3 hotspotPos = c.add(diskNormal.cross(diskRight).scale(Math.cos(hotspotAngle) * hotspotR))
                           .add(diskRight.scale(Math.sin(hotspotAngle) * hotspotR * 0.3));
        g.color(1.0f, 1.0f, 0.9f, (float) (0.4 * ease * (0.8 + 0.2 * Math.sin(activeAge * 0.15))))
                .sphere(hotspotPos, rh * 0.15, 8, 12);
    }

    private static void drawJets(Effect fx, double age, Geo g, Vec3 c, double rh, int seg,
                                  double ease, double activeProgress, double activeAge) {
        // Relativistic jets along rotation axis (perpendicular to disk)
        Vec3 jetAxis = new Vec3(Math.sin(0.35) * Math.cos(0.6), Math.cos(0.35), Math.sin(0.35) * Math.sin(0.6)).normalize();
        double jetLength = pullR * 0.8 * (1.0 + 0.3 * Math.sin(activeAge * 0.05));
        double jetWidth = rh * 0.4;

        for (int side = -1; side <= 1; side += 2) {
            Vec3 jetDir = jetAxis.scale(side);
            Vec3 jetBase = c.add(jetDir.scale(rh * 1.2));
            Vec3 jetTip = c.add(jetDir.scale(jetLength));

            // Jet core (collimated, bright)
            g.color(1.0f, 0.95f, 0.85f, (float) (0.6 * ease * (1.0 - activeProgress * 0.2)))
                    .ribbon(jetBase, jetTip, jetWidth * 0.3);

            // Jet sheath (wider, cooler)
            g.color(0.8f, 0.6f, 0.3f, (float) (0.3 * ease * (1.0 - activeProgress * 0.3)))
                    .ribbon(jetBase, jetTip, jetWidth * 0.7);

            // Jet glow at base
            g.color(1.0f, 0.8f, 0.4f, (float) (0.4 * ease * (1.0 + 0.3 * Math.sin(activeAge * 0.1))))
                    .sphere(jetBase, jetWidth * 0.8, 8, seg / 2);

            // Shock diamonds (Mach disks) along jet
            for (int d = 1; d <= 4; d++) {
                double diamondPos = d / 5.0;
                Vec3 diamondCenter = jetBase.add(jetDir.scale(jetLength * diamondPos));
                double diamondSize = jetWidth * (0.5 + 0.3 * Math.sin(activeAge * 0.2 + d * 1.5));
                g.color(1.0f, 0.9f, 0.6f, (float) (0.25 * ease * diamondSize / jetWidth))
                        .sphere(diamondCenter, diamondSize, 8, 12);
            }
        }
    }

    private static void drawFrameDragging(Effect fx, double age, Geo g, Vec3 c, double rh,
                                           double pullR, int seg, double activeAge) {
        // Frame-dragging: spacetime twisted around rotating black hole
        // Visualized as vortex lines wrapping around the rotation axis

        Vec3 spinAxis = new Vec3(Math.sin(0.35) * Math.cos(0.6), Math.cos(0.35), Math.sin(0.35) * Math.sin(0.6)).normalize();
        int vortexLines = seg >= 64 ? 12 : (seg >= 40 ? 8 : 5);

        for (int v = 0; v < vortexLines; v++) {
            double phase = v * Math.PI * 2.0 / vortexLines + activeAge * 0.02;
            double tilt = 0.6 * Math.sin(phase * 0.5);

            Vec3 prev = null;
            for (int i = 0; i <= 30; i++) {
                double t = i / 30.0;
                double radius = rh * 1.2 + (pullR * 0.7 - rh * 1.2) * Math.pow(t, 1.3);
                double height = (t - 0.5) * pullR * 0.8 * Math.sin(phase + t * 4.0);
                double angle = phase + t * 6.0 + Math.sin(t * 8.0) * 0.5;

                Vec3 dir = new Vec3(Math.cos(angle), 0, Math.sin(angle)).scale(radius);
                dir = dir.add(spinAxis.scale(height));

                if (prev != null) {
                    double fade = 1.0 - t;
                    g.color(0.3f, 0.7f, 1.0f, (float) (0.12 * fade * (0.3 + 0.7 * t)))
                            .ribbon(c.add(prev), c.add(dir), 0.015 + 0.02 * t);
                }
                prev = dir;
            }
        }

        // Ergosphere boundary (static limit)
        double ergoR = rh * 1.25; // Simplified ergosphere radius
        double ergoAlpha = 0.04 * (0.8 + 0.2 * Math.sin(activeAge * 0.05));
        g.color(0.4f, 0.8f, 1.0f, (float) ergoAlpha).sphere(c, ergoR, 12, seg);
    }

    private static void drawGravitationalWaves(Effect fx, double age, Geo g, Vec3 c, double pullR,
                                                int seg, double ease, double activeAge) {
        // Quadrupole gravitational waves - expanding ripples in spacetime
        for (int w = 0; w < 4; w++) {
            double ph = ((activeAge * 0.6 + w * 30.0) % 180.0) / 180.0;
            double waveR = ph * pullR * 1.2;
            double thickness = rh * (0.02 + 0.08 * ph);

            // Plus polarization
            g.color(0.5f, 0.65f, 1.0f, (float) ((1.0 - ph) * 0.25 * ease))
                    .ring(c, new Vec3(0, 1, 0), waveR, thickness, seg, 0, false);

            // Cross polarization (45 deg rotated)
            g.color(0.4f, 0.7f, 1.0f, (float) ((1.0 - ph) * 0.18 * ease))
                    .ring(c, new Vec3(1, 0, 0), waveR, thickness * 0.8, seg, 0, false);
        }
    }

    private static void drawCollapse(Effect fx, double age, Geo g, Vec3 c, Vec3 cam,
                                      double rh, double rph, double pullR, int seg,
                                      double ease, double activeEnd, double activeProgress) {
        double collapseProgress = clamp01((age - activeEnd) / Math.max(1, fx.duration - activeEnd));
        double implosionR = rh * (1.0 - collapseProgress * 0.95);
        float fade = (float) (1.0 - collapseProgress);

        if (implosionR > 0.02) {
            // Neutron star bounce flash
            if (collapseProgress < 0.15) {
                double bounce = 1.0 - collapseProgress / 0.15;
                g.color(1.0f, 0.95f, 0.8f, (float) (bounce * 0.95))
                        .sphere(c, implosionR * (0.3 + 1.7 * bounce), 10, 20);
            }

            // Collapsing horizon
            g.color(0.0f, 0.0f, 0.05f, 1.0f).sphere(c, implosionR, 16, seg);
            g.color(0.5f, 0.3f, 1.0f, (float) (0.6 * fade)).sphere(c, implosionR * 1.05, 12, seg);

            // Outward-propagating shockwave shells
            for (int i = 0; i < 5; i++) {
                double shellR = implosionR + collapseProgress * pullR * (0.3 + i * 0.25);
                double band = rh * (0.03 + 0.05 * i);
                float alpha = (float) ((1.0 - collapseProgress) * (0.5 - i * 0.08));
                g.color(0.6f, 0.4f, 1.0f, alpha).ring(c, new Vec3(0, 1, 0), shellR, band, seg, 0, false);
                g.color(0.4f, 0.6f, 1.0f, alpha * 0.7).ring(c, new Vec3(1, 0, 0), shellR, band * 0.7, seg, 0, false);
            }

            // Central singularity formation
            if (collapseProgress > 0.5) {
                double singularityR = implosionR * (1.0 - (collapseProgress - 0.5) * 2.0);
                if (singularityR > 0.01) {
                    g.color(0.0f, 0.0f, 0.0f, 1.0f).sphere(c, singularityR, 10, seg / 2);
                }
            }
        }
    }

    private static void blastGlow(Effect fx, double age, Geo g, Vec3 cam, int seg) {
        double t = clamp01(age / Math.max(1, fx.duration));
        Vec3 c = fx.pos;
        double maxR = fx.aux.x;

        if (t < 0.2) {
            // Initial singularity flash
            double s = t / 0.2;
            double flashR = maxR * 0.5 * (1.0 - s);
            g.color(1.0f, 0.98f, 0.85f, (float) ((1.0 - s) * 1.0)).sphere(c, flashR, 10, 20);
        }

        // Expanding shockwave - asymmetric (prolate)
        double shellR = maxR * t * 1.3;
        float fade = (float) (1.0 - t);

        // Main shell
        g.color(0.4f, 0.5f, 1.0f, 0.12f * fade).sphere(c, shellR, 16, seg / 2);

        // Multi-band shock front
        double band = 0.1 + 0.5 * fade;
        g.color(0.5f, 0.3f, 1.0f, 0.8f * fade).ring(c, new Vec3(0, 1, 0), shellR, band, seg, 0, false);
        g.color(0.3f, 0.7f, 1.0f, 0.6f * fade).ring(c, new Vec3(1, 0, 0), shellR, band * 0.8, seg, 0, false);
        g.color(0.3f, 0.7f, 1.0f, 0.6f * fade).ring(c, new Vec3(0, 0, 1), shellR, band * 0.8, seg, 0, false);
        g.color(1.0f, 0.9f, 0.7f, 0.7f * fade).ring(c, cam.subtract(c).normalize(), shellR * 0.85, band * 0.6, seg, 0, false);

        // Rayleigh-Taylor instabilities at shock front
        if (t > 0.3 && t < 0.8) {
            double instab = (t - 0.3) / 0.5;
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI * 2.0 / 8 + age * 0.1;
                Vec3 spike = c.add(new Vec3(Math.cos(angle), Math.sin(angle * 0.7), Math.sin(angle)).scale(shellR * (1.0 + instab * 0.3)));
                g.color(0.6f, 0.4f, 1.0f, (float) (0.3 * fade * instab)).sphere(spike, maxR * 0.04, 6, 10);
            }
        }
    }

    private static void drawFieldLines(Effect fx, double age, Geo g, Vec3 cam) {
        // Magnetic field lines - only during active phase
        int seg = segments(fx, cam);
        Vec3 c = fx.pos;
        double rh = horizonRadius(fx, age);
        double pullR = fx.aux.x;

        double form = clamp01(age / Math.max(1.0, fx.aux.z));
        if (form < 1.0) return;

        double activeStart = fx.aux.z;
        double activeEnd = activeStart + (fx.duration - activeStart) * 0.8;
        if (age < activeStart || age > activeEnd) return;

        double activeAge = age - activeStart;

        // Dipole field lines
        Vec3 dipoleAxis = new Vec3(Math.sin(0.35) * Math.cos(0.6), Math.cos(0.35), Math.sin(0.35) * Math.sin(0.6)).normalize();
        int fieldLines = seg >= 64 ? 16 : (seg >= 40 ? 10 : 6);

        for (int fl = 0; fl < fieldLines; fl++) {
            double phi = fl * Math.PI * 2.0 / fieldLines;
            Vec3 startDir = new Vec3(Math.cos(phi), 0, Math.sin(phi)).normalize();

            Vec3 prev = null;
            for (int i = 0; i <= 40; i++) {
                double t = i / 40.0;
                // Dipole field: r = r0 * sin^2(theta)
                double theta = Math.acos(clamp01(Math.abs(startDir.y)));
                double r0 = rh * 1.5;
                double radius = r0 * Math.sin(theta) * Math.sin(theta);
                if (radius < rh) radius = rh;

                double angle = phi + activeAge * 0.005 + t * 0.5;
                Vec3 pt = c.add(new Vec3(
                    Math.cos(angle) * radius * Math.sqrt(1 - startDir.y * startDir.y),
                    startDir.y * radius * 0.5,
                    Math.sin(angle) * radius * Math.sqrt(1 - startDir.y * startDir.y)
                ));

                if (prev != null) {
                    double fade = (1.0 - t) * 0.5;
                    g.color(0.3f, 0.6f, 1.0f, (float) (0.08 * fade))
                            .ribbon(prev, pt, 0.01 + 0.015 * t);
                }
                prev = pt;
            }
        }
    }
}