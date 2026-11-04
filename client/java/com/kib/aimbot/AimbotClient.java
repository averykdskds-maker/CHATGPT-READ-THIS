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
    private enum AttackMode {
        OFF, CLICK, HIT
    }

    private static final double PROJECTILE_DRAG = 0.99;
    private static final double ARROW_GRAVITY = 0.05;
    private static final int MAX_PROJECTILE_FLIGHT_TICKS = 100;

    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath("kib_aimbot", "controls")
    );

    private static KeyMapping toggleAimbotKey;
    private static KeyMapping pauseAimKey;

    private static boolean enabled = false;
    private static boolean aimPaused = false;
    private static boolean bowAim = true;
    private static boolean crossbowAim = true;
    private static AttackMode attackMode = AttackMode.OFF;

    private static int range = 48;
    private static int fov = 120;
    private static int smoothPercent = 65;
    private static int clicksPerSecond = 8;
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
        pauseAimKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
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
                    aimPaused = false;
                    feedback(context.getSource(), "Aimbot enabled");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    enabled = false;
                    aimPaused = false;
                    feedback(context.getSource(), "Aimbot disabled");
                    return 1;
                }))
                .then(ClientCommands.literal("pause")
                    .then(ClientCommands.literal("on").executes(context -> {
                        aimPaused = true;
                        feedback(context.getSource(), "Automatic aiming paused");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        aimPaused = false;
                        feedback(context.getSource(), "Automatic aiming resumed");
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
                .then(ClientCommands.literal("fov")
                    .then(ClientCommands.argument("degrees", IntegerArgumentType.integer(5, 180))
                        .executes(context -> {
                            fov = IntegerArgumentType.getInteger(context, "degrees");
                            feedback(context.getSource(), "Target FOV: " + fov + " degrees");
                            return 1;
                        })))
                .then(ClientCommands.literal("smooth")
                    .then(ClientCommands.argument("percent", IntegerArgumentType.integer(1, 100))
                        .executes(context -> {
                            smoothPercent = IntegerArgumentType.getInteger(context, "percent");
                            feedback(context.getSource(), "Aim smoothing: " + smoothPercent + "%");
                            return 1;
                        })))
                .then(ClientCommands.literal("cps")
                    .then(ClientCommands.argument("rate", IntegerArgumentType.integer(1, 20))
                        .executes(context -> {
                            clicksPerSecond = IntegerArgumentType.getInteger(context, "rate");
                            feedback(context.getSource(), "Auto-click rate: " + clicksPerSecond + " CPS");
                            return 1;
                        })))
                .then(ClientCommands.literal("click")
                    .then(ClientCommands.literal("on").executes(context -> {
                        attackMode = AttackMode.CLICK;
                        feedback(context.getSource(), "Auto-click enabled");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        if (attackMode == AttackMode.CLICK) attackMode = AttackMode.OFF;
                        feedback(context.getSource(), "Auto-click disabled");
                        return 1;
                    })))
                .then(ClientCommands.literal("hit")
                    .then(ClientCommands.literal("on").executes(context -> {
                        attackMode = AttackMode.HIT;
                        feedback(context.getSource(), "Auto-hit enabled (melee reach only)");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        if (attackMode == AttackMode.HIT) attackMode = AttackMode.OFF;
                        feedback(context.getSource(), "Auto-hit disabled");
                        return 1;
                    })))
                .then(ClientCommands.literal("bow")
                    .then(ClientCommands.literal("on").executes(context -> {
                        bowAim = true;
                        feedback(context.getSource(), "Bow prediction enabled");
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(context -> {
                        bowAim = false;
                        feedback(context.getSource(), "Bow prediction disabled");
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
            );
        });
    }

    private static void onClientTick(Minecraft client) {
        tickCounter++;

        // Key mappings are handled only while the game is in-world, not while a
        // menu or the Controls screen is consuming keyboard input.
        if (client.gui.screen() == null) {
            while (toggleAimbotKey.consumeClick()) {
                enabled = !enabled;
                if (enabled) aimPaused = false;
                notifyPlayer(client, "Aimbot " + (enabled ? "enabled" : "disabled"));
            }

            while (pauseAimKey.consumeClick()) {
                aimPaused = !aimPaused;
                notifyPlayer(client, aimPaused
                    ? "Auto aimbot OFF — press again to resume"
                    : "Auto aimbot ON");
            }
        }

        if (!enabled || aimPaused || client.player == null || client.level == null || client.gameMode == null) {
            return;
        }

        LocalPlayer player = client.player;
        ItemStack held = player.getMainHandItem();
        boolean holdingBow = held.is(Items.BOW);
        boolean holdingCrossbow = held.is(Items.CROSSBOW);
        boolean drawingBow = holdingBow && player.isUsingItem() && player.getUseItem().is(Items.BOW);

        // Let the player eat, drink, block, or use a healing item without the
        // camera being pulled back toward a nearby target. Drawing a bow remains
        // eligible for projectile prediction.
        if (player.isUsingItem() && !drawingBow) {
            return;
        }

        boolean projectileAim = false;
        double projectileSpeed = 0.0;

        if (drawingBow && bowAim) {
            double draw = Math.min(1.0, player.getTicksUsingItem() / 20.0);
            double power = (draw * draw + 2.0 * draw) / 3.0;
            projectileSpeed = power * 3.0;
            projectileAim = projectileSpeed > 0.15;
        } else if (holdingCrossbow && crossbowAim && CrossbowItem.isCharged(held)) {
            projectileSpeed = 3.15;
            projectileAim = true;
        }

        // Do not try melee auto-attacks while holding a ranged weapon.
        if ((holdingBow || holdingCrossbow) && !projectileAim) {
            return;
        }

        Player target = findTarget(client, player);
        if (target == null) return;

        Vec3 aimPoint = target.position().add(0.0, target.getBbHeight() * 0.55, 0.0);
        if (projectileAim) {
            aimPoint = predictProjectileAim(player, target, aimPoint, projectileSpeed);
        }

        rotateTowards(player, aimPoint);

        if (!projectileAim) {
            tryAutoAttack(client, player, target);
        }
    }

    private static Player findTarget(Minecraft client, LocalPlayer player) {
        AABB searchBox = player.getBoundingBox().inflate(range);
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).normalize();
        double minimumDot = Math.cos(Math.toRadians(fov / 2.0));

        return client.level.getEntitiesOfClass(Player.class, searchBox, candidate ->
                candidate != player
                    && candidate.isAlive()
                    && !candidate.isSpectator()
                    && player.hasLineOfSight(candidate)
                    && !candidate.isInvisibleTo(player)
                    && !player.isAlliedTo(candidate)
            ).stream()
            .filter(candidate -> {
                Vec3 direction = candidate.position()
                    .add(0.0, candidate.getBbHeight() * 0.55, 0.0)
                    .subtract(eye)
                    .normalize();
                return direction.dot(look) >= minimumDot;
            })
            .min(Comparator.comparingDouble(candidate -> targetScore(player, candidate, eye, look)))
            .orElse(null);
    }

    private static double targetScore(LocalPlayer player, Player target, Vec3 eye, Vec3 look) {
        Vec3 direction = target.position()
            .add(0.0, target.getBbHeight() * 0.55, 0.0)
            .subtract(eye)
            .normalize();
        double angleCost = 1.0 - Math.max(-1.0, Math.min(1.0, direction.dot(look)));
        return angleCost * 100.0 + player.distanceTo(target);
    }

    /**
     * Solves the arrow's discrete flight path over candidate flight times.
     *
     * The calculation includes per-tick air drag, gravity, target motion, and
     * the velocity inherited from the shooter. It chooses the reachable flight
     * time whose required launch speed is closest to the bow's actual charge.
     */
    private static Vec3 predictProjectileAim(LocalPlayer shooter, Player target, Vec3 targetPoint, double launchSpeed) {
        if (!Double.isFinite(launchSpeed) || launchSpeed <= 0.05) {
            return targetPoint;
        }

        Vec3 origin = shooter.getEyePosition();
        Vec3 targetVelocity = target.getDeltaMovement();
        Vec3 shooterVelocity = shooter.getDeltaMovement();

        // Vanilla arrows inherit the shooter's horizontal motion, and inherit
        // vertical motion only while airborne.
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

            // Gravity acts after the first movement step. This sums its
            // accumulated displacement while respecting the same air drag.
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

            // Prefer the first close solution, which is normally the flatter
            // trajectory rather than a needlessly high arc.
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
        if (attackMode == AttackMode.OFF || !player.hasLineOfSight(target)) return;

        // Match vanilla's input preconditions. Never attack while a screen is open
        // or the game does not have mouse focus.
        if (client.gui.screen() != null || !client.mouseHandler.isMouseGrabbed()) return;

        // Only request an attack when Minecraft's own current raycast has
        // selected this exact player. Normal vanilla reach checks still apply.
        if (!(client.hitResult instanceof EntityHitResult entityHit)
                || entityHit.getEntity() != target) {
            return;
        }

        if (player.isUsingItem() || player.getAttackStrengthScale(0.5F) < 0.92F) return;

        int interval = attackMode == AttackMode.HIT
                ? 1
                : Math.max(1, 20 / clicksPerSecond);
        if (tickCounter - lastAttackTick < interval) return;

        if (((MinecraftAccessor) (Object) client).kib$invokeStartAttack()) {
            lastAttackTick = tickCounter;
        }
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
            + " | aim " + (aimPaused ? "PAUSED" : "ACTIVE")
            + " | range " + range
            + " | FOV " + fov
            + " | smooth " + smoothPercent + "%"
            + " | attack " + attackMode.name().toLowerCase()
            + " | bow " + (bowAim ? "ON" : "OFF")
            + " | crossbow " + (crossbowAim ? "ON" : "OFF")
            + " | " + clicksPerSecond + " CPS";
    }

    private static void feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[Kib Aimbot] " + message));
    }
}
