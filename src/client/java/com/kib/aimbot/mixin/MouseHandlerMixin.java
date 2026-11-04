package com.kib.aimbot.mixin;

import com.kib.aimbot.AimbotClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reports physical mouse deltas to Kib Aim before Minecraft applies them.
 * Programmatic camera rotations do not pass through this input hook.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "handleAccumulatedMovement()V", at = @At("HEAD"))
    private void kib$onMouseMovement(CallbackInfo ci) {
        AimbotClient.onMouseMovement(accumulatedDX, accumulatedDY);
    }
}
