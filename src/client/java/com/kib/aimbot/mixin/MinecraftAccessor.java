package com.kib.aimbot.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Calls Minecraft's own attack entry point so the game uses its current
 * crosshair hit result and built-in item attack-range checks.
 */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Invoker("startAttack")
    boolean kib$invokeStartAttack();
}
