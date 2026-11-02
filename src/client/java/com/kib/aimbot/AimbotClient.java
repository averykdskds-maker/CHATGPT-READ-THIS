package com.kib.aimbot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

public final class AimbotClient implements ClientModInitializer {
    private enum AttackMode {
        OFF, CLICK, HIT
    }

    private static boolean enabled = false;
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
        ClientTickEvents.END_CLIENT_TICK.register(AimbotClient::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("aimbot")
                .then(ClientCommands.literal("on").executes(context -> {
                    enabled = true;
                    feedback(context.getSource(), "Aimbot enabled");
                    return 1;
                }))
                .then(ClientCommands.literal("off").executes(context -> {
                    enabled = false;
                    feedback(context.getSource(), "Aimbot disabled");
                    return 1;
                }))
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
                    .then(ClientCommands.literal("crossbow_off").executes(context -> {
                        crossbowAim = false;
                        feedback(context.getSource(), "Crossbow prediction disabled");
                        return 1;
                    })))
            );
        });
    }

    private static void onClientTick(Minecraft client) {
        tickCounter++;
        if (!enabled || client.player == null || client.level == null || client.gameMode == null || client.screen != null) {
            return;
        }

        LocalPlayer player = client.player;
        ItemStack held = player.getMainHandItem();
        boolean holdingBow = held.is(Items.BOW);
        boolean holdingCrossbow = held.is(Items.CROSSBOW);

        boolean projectileAim = false;
        double projectileSpeed = 0.0;

        if (holdingBow && bowAim && player.isUsingItem() && player.getUseItem().is(Items.BOW)) {
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

        Vec3 aimPoint = target.position().add(0.0, target.getBbHeight() * 0.78, 0.0);
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
                    .add(0.0, candidate.getBbHeight() * 0.65, 0.0)
                    .subtract(eye)
                    .normalize();
                return direction.dot(look) >= minimumDot;
            })
            .min(Comparator.comparingDouble(candidate -> targetScore(player, candidate, eye, look)))
            .orElse(null);
    }

    private static double targetScore(LocalPlayer player, Player target, Vec3 eye, Vec3 look) {
        Vec3 direction = target.position()
            .add(0.0, target.getBbHeight() * 0.65, 0.0)
            .subtract(eye)
            .normalize();
        double angleCost = 1.0 - Math.max(-1.0, Math.min(1.0, direction.dot(look)));
        return angleCost * 100.0 + player.distanceTo(target);
    }

    /**
     * Leads the target by its velocity relative to the shooter and compensates for
     * the arrow's approximate gravity and air drag. Time is estimated in game ticks.
     */
    private static Vec3 predictProjectileAim(LocalPlayer shooter, Player target, Vec3 targetPoint, double launchSpeed) {
        double distance = shooter.getEyePosition().distanceTo(targetPoint);
        double travelled = 0.0;
        int flightTicks = 1;

        for (; flightTicks < 80; flightTicks++) {
            travelled += launchSpeed * Math.pow(0.99, flightTicks - 1);
            if (travelled >= distance) break;
        }

        Vec3 relativeVelocity = target.getDeltaMovement().subtract(shooter.getDeltaMovement());
        Vec3 lead = targetPoint.add(relativeVelocity.scale(flightTicks));
        double gravityCompensation = 0.5 * 0.05 * flightTicks * flightTicks;
        return lead.add(0.0, gravityCompensation, 0.0);
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
        if (player.getAttackStrengthScale(0.5F) < 0.92F) return;

        double distance = player.distanceTo(target);
        if (attackMode == AttackMode.HIT && distance > 3.2) return;
        if (attackMode == AttackMode.CLICK && distance > 5.0) return;

        int interval = Math.max(1, 20 / clicksPerSecond);
        if (tickCounter - lastAttackTick < interval) return;

        client.gameMode.attack(player, target);
        lastAttackTick = tickCounter;
    }

    private static float wrapDegrees(float degrees) {
        float result = degrees % 360.0F;
        if (result >= 180.0F) result -= 360.0F;
        if (result < -180.0F) result += 360.0F;
        return result;
    }

    private static String status() {
        return "Aimbot " + (enabled ? "ON" : "OFF")
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
