package dev.durabilityarmor.test.mixin;

import dev.durabilityarmor.test.DurabilityArmorIntegrationTests;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class TestServerMixin {
    @Unique private boolean da$tested;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void da$test(BooleanSupplier timeLeft, CallbackInfo ci) {
        if (da$tested) return;
        da$tested = true;
        MinecraftServer server = (MinecraftServer) (Object) this;
        DurabilityArmorIntegrationTests.run(server);
        server.halt(false);
    }
}
