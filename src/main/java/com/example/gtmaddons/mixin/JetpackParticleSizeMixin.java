package com.example.gtmaddons.mixin;

import com.example.gtmaddons.JetpackParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Each particle made while a jetpack's exhaust or a gun tracer packet is handled gets its set size (JetpackParticles, GunTracers). */
@Mixin(ParticleEngine.class)
public abstract class JetpackParticleSizeMixin {

	@Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;", at = @At("RETURN"), require = 0)
	private void gtmaddons$size(ParticleOptions options, double x, double y, double z, double dx, double dy, double dz, CallbackInfoReturnable<Particle> cir) {
		float size = JetpackParticles.sizeNow();
		if (size != 1f && cir.getReturnValue() != null) cir.getReturnValue().scale(size);
	}
}
