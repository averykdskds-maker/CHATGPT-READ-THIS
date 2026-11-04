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

    private static final float MANUAL_MOUSE_THRESHOLD_DEGREES = 1.5F;
    private static final double MANUAL_REACQUIRE_COS = Math.cos(Math.toRadians(8.0));

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

    private static boolean hasObservedRotation = false;
    private static float lastObservedYaw;
    private static float lastObservedPitch;

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
                    hasObservedRotation = false;
                    feedback(context.getSource(), "Aimbot enabled");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    enabled = false;
                    manualOverrideActive = false;
                    hasObservedRotation = false;
                    feedback(context.getSource(), "Aimbot disabled");
                    return 1;
                }))
                .then(ClientCommands.literal("autooff")
                    .then(ClientCommands.literal("on").executes(context -> {
                        autoAimOffEnabled = true;
                        manualOverrideActive = false;
                        hasObservedRotation = false;
                        feedback(context.getSource(), "Manual mouse override enabled");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        autoAimOffEnabled = false;
                        manualOverrideActive = false;
                        hasObservedRotation = false;
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
            hasObservedRotation = false;
            return;
        }

        LocalPlayer player = client.player;

        // Detect actual camera movement between ticks. While manual override is
        // enabled, player input takes priority over automated rotation.
        if (hasObservedRotation && autoAimOffEnabled) {
            float yawInput = Math.abs(wrapDegrees(player.getYRot() - lastObservedYaw));
            float pitchInput = Math.abs(player.getXRot() - lastObservedPitch);
            if (Math.hypot(yawInput, pitchInput) >= MANUAL_MOUSE_THRESHOLD_DEGREES) {
                manualOverrideActive = true;
            }
        }

        if (client.gui.screen() != null) {
            recordObservedRotation(player);
            return;
        }

        while (toggleAimbotKey.consumeClick()) {
            enabled = !enabled;
            manualOverrideActive = false;
            hasObservedRotation = false;
            notifyPlayer(client, "Aimbot " + (enabled ? "enabled" : "disabled"));
        }

        while (autoAimOffKey.consumeClick()) {
            autoAimOffEnabled = !autoAimOffEnabled;
            manualOverrideActive = false;
            hasObservedRotation = false;
            notifyPlayer(client, autoAimOffEnabled
                ? "Auto Aimbot Off enabled — manual mouse movement overrides aiming"
                : "Auto Aimbot Off disabled — continuous aiming enabled");
        }

        if (!enabled || client.level == null || client.gameMode == null) {
            recordObservedRotation(player);
            return;
        }

        ItemStack held = player.getMainHandItem();
        boolean holdingBow = held.is(Items.BOW);
        boolean holdingCrossbow = held.is(Items.CROSSBOW);

        // Wurst already handles bow aiming. Kib Aim deliberately does not
        // rotate for bows, or while the player is drawing one.
        if (holdingBow) {
            recordObservedRotation(player);
            return;
        }

        // Keep crossbow trajectory support independent from bow aiming.
        boolean projectileAim = holdingCrossbow && crossbowAim && CrossbowItem.isCharged(held);
        if (holdingCrossbow && !projectileAim) {
            recordObservedRotation(player);
            return;
        }

        // Do not pull the camera while eating, drinking, blocking, or using a
        // healing item. A charged crossbow is handled above.
        if (player.isUsingItem()) {
            recordObservedRotation(player);
            return;
        }

        Player target = findNearestTarget(client, player);
        if (target == null) {
            manualOverrideActive = false;
            recordObservedRotation(player);
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
            if (!isLookingAt(player, aimPoint)) {
                recordObservedRotation(player);
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
        recordObservedRotation(player);
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

    private static boolean isLookingAt(LocalPlayer player, Vec3 targetPoint) {
        Vec3 direction = targetPoint.subtract(player.getEyePosition()).normalize();
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
        Vec3 targetVelocity = target.getDeltaMovement();
        Vec3 shooterVelocity = shooter.getDeltaMovement();

        Vec3 inheritedVelocity = new Vec3(
            shooterVelocity.x,
            shooter.onGround() ? 0.0 : shooterVelocity.y,
            shooterVelocity.z
        );

        double dragSum = 0.0;
        double dragMultiplier = 1.0;
        double bestSpeedError = Double.MAX_VALUE;
        Vec3 bestDirection = targetPoint.subtract(origin).normalize();

        for (int flightTicks = 1; flightTicks <= MAX_PROJECTILE_FLIGHT_TICKS; flightTicks++) {
            dragSum += dragMultiplier;
            dragMultiplier *= PROJECTILE_DRAG;

            double gravityDrop = -ARROW_GRAVITY / (1.0 - PROJECTILE_DRAG)
                * (flightTicks - dragSum);

            Vec3 futureTarget = targetPoint.add(targetVelocity.scale(flightTicks));
            Vec3 inheritedDisplacement = inheritedVelocity.scale(dragSum);

            Vec3 requiredDisplacement = futureTarget
                .subtract(origin)
                .subtract(inheritedDisplacement)
                .subtract(0.0, gravityDrop, 0.0);

            Vec3 requiredLaunchVelocity = requiredDisplacement.scale(1.0 / dragSum);
            double speedError = Math.abs(requiredLaunchVelocity.length() - launchSpeed);

            if (speedError < bestSpeedError && requiredLaunchVelocity.lengthSqr() > 1.0E-8) {
                bestSpeedError = speedError;
                bestDirection = requiredLaunchVelocity.normalize();
            }

            if (speedError <= Math.max(0.02, launchSpeed * 0.005)) {
                break;
            }
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

    private static void recordObservedRotation(LocalPlayer player) {
        lastObservedYaw = player.getYRot();
        lastObservedPitch = player.getXRot();
        hasObservedRotation = true;
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
