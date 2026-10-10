package com.example.gtmaddons.gun;

import com.example.gtmaddons.PvpCategory;

import java.util.List;

/**
 * One gun shot and what came of it.
 *
 * @param gun          gun name without the ammo readout, e.g. "Combat MG"
 * @param target       who the shot damaged, or null on a miss
 * @param distance     blocks to the target when hit, or NaN
 * @param gunshotSound the sound played at your position with the shot, if any
 * @param responseMs   click-to-shot time for the first shot of a trigger pull, or null
 * @param otherSounds  any other sounds matched to this shot
 * @param category     which kind of PvP you were in when the shot was fired
 * @param speedBeforeBps movement guns only (else null): horizontal speed in blocks/s the frame before the shot
 * @param speedAfterBps  movement guns only (else null): peak horizontal speed in blocks/s just after it
 * @param netted       the shot was fired at a player caught in a Net Launcher net (within 25 ticks of the net hitting them)
 */
public record ShotResult(
		long timestampMillis,
		String gun,
		int ammoBefore,
		int ammoAfter,
		boolean hit,
		boolean headshot,
		boolean kill,
		String target,
		double distance,
		String gunshotSound,
		Long responseMs,
		int pingMs,
		List<String> otherSounds,
		PvpCategory category,
		Double speedBeforeBps,
		Double speedAfterBps,
		boolean netted
) {
}
