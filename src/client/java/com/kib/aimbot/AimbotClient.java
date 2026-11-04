package com.kib.aimbot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.blaze3d.platform.InputConstants;
import com.kib.aimbot.mixin.MinecraftAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

public final class AimbotClient implements ClientModInitializer {
    private static final double PROJECTILE_DRAG = 0.99;
    private static final double ARROW_GRAVITY = 0.05;
    private static final int MAX_PROJECTILE_FLIGHT_TICKS = 100;

    private static final double MANUAL_REACQUIRE_COS = Math.cos(Math.toRadians(20.0));

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("kib_aimbot", "controls")
    );

    private static KeyMapping toggleAimbotKey;
    private static KeyMapping autoAimOffKey;

    private static boolean enabled = false;
    private static boolean autoAimOffEnabled = true;
    private static boolean manualOverrideActive = false;
    private static boolean autoAttackEnabled = false;
    private static boolean crossbowAim = true;

    private static int range = 48;
    private static int smoothPercent = 65;
    private static int tickCounter = 0;
    private static int lastAttackTick = -100;

    @Override
    public void onInitializeClient() {
        toggleAimbotKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kib_aimbot.toggle",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_R,
            KEY_CATEGORY
        ));
        autoAimOffKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.kib_aimbot.pause",
            InputConstants.Type.KEYBOARD,
            InputConstants.KEY_V,
            KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(AimbotClient::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("aimbot")
                .then(ClientCommands.literal("on").executes(context -> {
                    enabled = true;
                    manualOverrideActive = false;
                    feedback(context.getSource(), "Aimbot enabled");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    enabled = false;
                    manualOverrideActive = false;
                    feedback(context.getSource(), "Aimbot disabled");
                    return 1;
                }))
                .then(ClientCommands.literal("autooff")
                    .then(ClientCommands.literal("on").executes(context -> {
                        autoAimOffEnabled = true;
                        manualOverrideActive = false;
                            feedback(context.getSource(), "Manual mouse override enabled");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        autoAimOffEnabled = false;
                        manualOverrideActive = false;
                            feedback(context.getSource(), "Manual mouse override disabled");
                        return 1;
                    })))
                .then(ClientCommands.literal("attack")
                    .then(ClientCommands.literal("on").executes(context -> {
                        autoAttackEnabled = true;
                        feedback(context.getSource(), "Vanilla-reach auto-attack enabled");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        autoAttackEnabled = false;
                        feedback(context.getSource(), "Auto-attack disabled");
                        return 1;
                    })))
                .then(ClientCommands.literal("crossbow")
                    .then(ClientCommands.literal("on").executes(context -> {
                        crossbowAim = true;
                        feedback(context.getSource(), "Crossbow prediction enabled");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        crossbowAim = false;
                        feedback(context.getSource(), "Crossbow prediction disabled");
                        return 1;
                    })))
                .then(ClientCommands.literal("status").executes(context -> {
                    feedback(context.getSource(), status());
                    return 1;
                }))
                .then(ClientCommands.literal("range")
                    .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(3, 128))
                        .executes(context -> {
                            range = IntegerArgumentType.getInteger(context, "blocks");
                            feedback(context.getSource(), "Target range: " + range + " blocks");
                            return 1;
                        })))
                .then(ClientCommands.literal("smooth")
                    .then(ClientCommands.argument("percent", IntegerArgumentType.integer(1, 100))
                        .executes(context -> {
                            smoothPercent = IntegerArgumentType.getInteger(context, "percent");
                            feedback(context.getSource(), "Aim smoothing: " + smoothPercent + "%");
                            return 1;
                        })))
            );
        });
    }

    private static void onClientTick(Minecraft client) {
        tickCounter++;

        if (client.player == null) {
            return;
        }

        LocalPlayer player = client.player;

        if (client.gui.screen() != null) {
            return;
        }

        while (toggleAimbotKey.consumeClick()) {
            enabled = !enabled;
            manualOverrideActive = false;
            notifyPlayer(client, "Aimbot " + (enabled ? "enabled" : "disabled"));
        }

        while (autoAimOffKey.consumeClick()) {
            autoAimOffEnabled = !autoAimOffEnabled;
            manualOverrideActive = false;
            notifyPlayer(client, autoAimOffEnabled
                ? "Auto Aimbot Off enabled — manual mouse movement overrides aiming"
                : "Auto Aimbot Off disabled — continuous aiming enabled");
        }

        if (!enabled || client.level == null || client.gameMode == null) {
                        return;
        }

        ItemStack held = player.getMainHandItem();
        boolean holdingBow = held.is(Items.BOW);
        boolean holdingCrossbow = held.is(Items.CROSSBOW);

        // Wurst already handles bow aiming. Kib Aim deliberately does not
        // rotate for bows, or while the player is drawing one.
        if (holdingBow) {
                        return;
        }

        // Keep crossbow trajectory support independent from bow aiming.
        boolean projectileAim = holdingCrossbow && crossbowAim && CrossbowItem.isCharged(held);
        if (holdingCrossbow && !projectileAim) {
                        return;
        }

        // Do not pull the camera while eating, drinking, blocking, or using a
        // healing item. Keep charged-crossbow tracking active while it is held.
        if (player.isUsingItem() && !projectileAim) {
            return;
        }

        Player target = findNearestTarget(client, player);
        if (target == null) {
            // Keep manual override latched while no eligible target is visible.
            // It releases only when the player's view returns near a valid target.
            return;
        }

        Vec3 aimPoint = target.position().add(0.0, target.getBbHeight() * 0.55, 0.0);
        if (projectileAim) {
            aimPoint = predictProjectileAim(player, target, aimPoint, 3.15);
        }

        if (manualOverrideActive) {
            // Do not snap back to the target after the player deliberately
            // looks away. Resume only when the player's own view returns close
            // to the nearest target.
            if (!isLookingAt(player, target)) {
                                return;
            }
            manualOverrideActive = false;
        }

        // Auto-attacks are evaluated against the current vanilla hit result
        // BEFORE this tick's camera rotation. This prevents an attack based on
        // a newly-written rotation with a stale crosshair result.
        if (!projectileAim) {
            tryAutoAttack(client, player, target);
        }

        rotateTowards(player, aimPoint);
            }

    /**
     * Selects the closest eligible player by physical distance, not by how
     * close that player is to the current crosshair. Thus looking at a distant
     * player cannot steal the target from a nearer visible player.
     */
    private static Player findNearestTarget(Minecraft client, LocalPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(range);

        return client.level.getEntitiesOfClass(Player.class, searchBox, candidate ->
                candidate != player
                    && candidate.isAlive()
                    && !candidate.isSpectator()
                    && player.distanceToSqr(candidate) <= (double) range * range
                    && player.hasLineOfSight(candidate)
                    && !candidate.isInvisibleTo(player)
                    && !player.isAlliedTo(candidate)
            ).stream()
            .min(Comparator.comparingDouble(player::distanceToSqr))
            .orElse(null);
    }

    private static boolean isLookingAt(LocalPlayer player, Player target) {
        // Reacquire against the opponent's visible centre, not the calculated
        // ballistic aim point (which can sit above the target).
        Vec3 targetCentre = target.position().add(0.0, target.getBbHeight() * 0.55, 0.0);
        Vec3 direction = targetCentre.subtract(player.getEyePosition()).normalize();
        Vec3 look = player.getViewVector(1.0F).normalize();
        return look.dot(direction) >= MANUAL_REACQUIRE_COS;
    }

    /**
     * Leads a moving crossbow target and approximates arrow drag, gravity, and
     * shooter velocity. Bow prediction is intentionally omitted; Wurst owns it.
     */
    private static Vec3 predictProjectileAim(LocalPlayer shooter, Player target, Vec3 targetPoint, double launchSpeed) {
        if (!Double.isFinite(launchSpeed) || launchSpeed <= 0.05) {
            return targetPoint;
        }

        Vec3 origin = shooter.getEyePosition();
        double distance = targetPoint.distanceTo(origin);
        if (distance < 1.0E-4) {
            return targetPoint;
        }

        // Client-side velocity can briefly be stale or spike during corrections.
        // Clamp it to plausible player movement so one bad sample cannot send
        // the crossbow aim far above the opponent.
        Vec3 rawTargetVelocity = target.getDeltaMovement();
        double horizontalSpeed = Math.sqrt(
            rawTargetVelocity.x * rawTargetVelocity.x
                + rawTargetVelocity.z * rawTargetVelocity.z
        );
        double horizontalScale = horizontalSpeed > 0.45 ? 0.45 / horizontalSpeed : 1.0;
        Vec3 targetVelocity = new Vec3(
            rawTargetVelocity.x * horizontalScale,
            Math.max(-0.35, Math.min(0.35, rawTargetVelocity.y)),
            rawTargetVelocity.z * horizontalScale
        );

        Vec3 shooterVelocity = shooter.getDeltaMovement();
        Vec3 inheritedVelocity = new Vec3(
            shooterVelocity.x,
            shooter.onGround() ? 0.0 : shooterVelocity.y,
            shooterVelocity.z
        );

        // Restrict the search to the expected low-arc flight time. Searching
        // long after the arrow could plausibly arrive encourages steep arcs.
        double estimatedFlightTicks = distance / launchSpeed;
        int minFlightTicks = Math.max(1, (int) Math.floor(estimatedFlightTicks) - 3);
        int maxFlightTicks = Math.min(
            MAX_PROJECTILE_FLIGHT_TICKS,
            Math.max(minFlightTicks, (int) Math.ceil(estimatedFlightTicks) + 5)
        );

        double bestSpeedError = Double.MAX_VALUE;
        Vec3 bestDirection = null;

        for (int flightTicks = minFlightTicks; flightTicks <= maxFlightTicks; flightTicks++) {
            double dragSum = 0.0;
            double dragMultiplier = 1.0;
            for (int tick = 0; tick < flightTicks; tick++) {
                dragSum += dragMultiplier;
                dragMultiplier *= PROJECTILE_DRAG;
            }

            double gravityDrop = -ARROW_GRAVITY / (1.0 - PROJECTILE_DRAG)
                * (flightTicks - dragSum);

            Vec3 futureTarget = targetPoint.add(targetVelocity.scale(flightTicks));
            Vec3 inheritedDisplacement = inheritedVelocity.scale(dragSum);

            Vec3 requiredDisplacement = futureTarget
                .subtract(origin)
                .subtract(inheritedDisplacement)
                .subtract(0.0, gravityDrop, 0.0);

            Vec3 requiredLaunchVelocity = requiredDisplacement.scale(1.0 / dragSum);
            double requiredSpeed = requiredLaunchVelocity.length();
            if (requiredSpeed <= 1.0E-8) {
                continue;
            }

            double pitch = Math.toDegrees(Math.asin(
                Math.max(-1.0, Math.min(1.0, requiredLaunchVelocity.y / requiredSpeed))
            ));
            if (Math.abs(pitch) > 35.0) {
                continue;
            }

            double speedError = Math.abs(requiredSpeed - launchSpeed);
            if (speedError < bestSpeedError) {
                bestSpeedError = speedError;
                bestDirection = requiredLaunchVelocity.scale(1.0 / requiredSpeed);
            }
        }

        // Prefer direct target-centre aiming when no credible low-arc solution
        // exists. The solution is recomputed every client tick as the target moves.
        double acceptableSpeedError = Math.max(0.15, launchSpeed * 0.12);
        if (bestDirection == null || bestSpeedError > acceptableSpeedError) {
            return targetPoint;
        }

        return origin.add(bestDirection.scale(16.0));
    }

    private static void rotateTowards(LocalPlayer player, Vec3 target) {
        Vec3 delta = target.subtract(player.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));

        float factor = smoothPercent / 100.0F;
        float yaw = player.getYRot() + wrapDegrees(desiredYaw - player.getYRot()) * factor;
        float pitch = player.getXRot() + (desiredPitch - player.getXRot()) * factor;
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));

        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
    }

    private static void tryAutoAttack(Minecraft client, LocalPlayer player, Player target) {
        if (!autoAttackEnabled || !player.hasLineOfSight(target)) return;
        if (client.gui.screen() != null || !client.mouseHandler.isMouseGrabbed()) return;

        // Only use Minecraft's current vanilla raycast result. startAttack()
        // then applies the ordinary weapon cooldown and interaction-range rules.
        if (!(client.hitResult instanceof EntityHitResult entityHit)
                || entityHit.getEntity() != target) {
            return;
        }

        if (player.isUsingItem() || player.getAttackStrengthScale(0.5F) < 0.92F) return;
        if (tickCounter - lastAttackTick < 1) return;

        if (((MinecraftAccessor) (Object) client).kib$invokeStartAttack()) {
            lastAttackTick = tickCounter;
        }
    }

    /**
     * Called by the MouseHandler mixin with real accumulated mouse deltas.
     * Camera changes made by Kib Aim never enter this path, so tracking a
     * moving target cannot accidentally trigger the manual override.
     */
    public static void onMouseMovement(double deltaX, double deltaY) {
        if (!autoAimOffEnabled || !enabled) return;
        if (Math.abs(deltaX) + Math.abs(deltaY) < 1.0E-4) return;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !client.mouseHandler.isMouseGrabbed()) return;

        manualOverrideActive = true;
    }

    private static void notifyPlayer(Minecraft client, String message) {
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal("[Kib Aimbot] " + message));
        }
    }

    private static float wrapDegrees(float degrees) {
        float result = degrees % 360.0F;
        if (result >= 180.0F) result -= 360.0F;
        if (result < -180.0F) result += 360.0F;
        return result;
    }

    private static String status() {
        return "Aimbot " + (enabled ? "ON" : "OFF")
            + " | manual override " + (autoAimOffEnabled ? "ON" : "OFF")
            + " | override active " + (manualOverrideActive ? "YES" : "NO")
            + " | nearest-target range " + range
            + " | smooth " + smoothPercent + "%"
            + " | auto-attack " + (autoAttackEnabled ? "ON" : "OFF")
            + " | crossbow " + (crossbowAim ? "ON" : "OFF");
    }

    private static void feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[Kib Aimbot] " + message));
    }
}
