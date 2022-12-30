package com.simonmicro.dont_run_with_knives;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.item.SwordItem;
import net.minecraft.nbt.NbtCompound;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DontRunWithKnivesMod implements ModInitializer {
	public static final String MOD_ID = "dont_run_with_knives";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private class FallGuy {
		float healthBeforeFall;
		float standingTimeoutTicks = 20; // 1/5 second
	}

	private class EmotionalDamage {
		float extraDamage = 0;
		float burnTickTime = 0; // if > 0, the player is burning
	}

	/**
	 * Calculates the extra damage and burn time for a given item and fall damage.
	 * 
	 * @param itemStack
	 * @param normalDamage "base" damage without any modifications
	 * @return
	 */
	private EmotionalDamage calcExtraDamage(ItemStack itemStack, float normalDamage) {
		final Item item = itemStack.getItem();
		EmotionalDamage eD = new EmotionalDamage();
	
		if(item != null && item.getClass() == SwordItem.class) {
			// A diamond sword is 100% damage, a Netherite sword is 125% damage
			final float multiplier = (((SwordItem) item).getAttackDamage() + 1) / (((SwordItem) Items.DIAMOND_SWORD).getAttackDamage() + 1);

			// Now get the enchantments and their levels from the players sword
			short sharpnessLevel = 0;
			short hotnessLevel = 0;
			try {
				for(var nbt: itemStack.getNbt().getList("Enchantments", NbtType.COMPOUND)) {
					try {
						NbtCompound enchantment = (NbtCompound) nbt;
						if(enchantment.getString("id").equals("minecraft:sharpness"))
							sharpnessLevel += enchantment.getShort("lvl");
						if(enchantment.getString("id").equals("minecraft:fire_aspect"))
						 	hotnessLevel += enchantment.getShort("lvl");
					} catch (Exception e) {
						LOGGER.warn("Unexpected exception while parsing NBT-element of item-stack data: " + e.toString());
					}
				}
			} catch (Exception e) {
				LOGGER.warn("Unexpected exception while parsing NBT item-stack data: " + e.toString());
			}

			final float sharpnessLevelExtraDamage = (sharpnessLevel > 0) ? (0.5f * (sharpnessLevel - 1) + 1) : 0;
			eD.extraDamage = normalDamage * multiplier + sharpnessLevelExtraDamage;
			eD.burnTickTime = hotnessLevel * 80;
		}
		return eD;
	}

	@Override
	public void onInitialize() {
		Map<ServerPlayerEntity, FallGuy> fallingGuys = new HashMap<>();
		ServerTickEvents.START_WORLD_TICK.register(server -> {
			for(ServerPlayerEntity p : server.getPlayers()) {
				if(p.fallDistance > 3 && !p.isFallFlying()) {
					BlockPos pos = p.getBlockPos();
					// Now determine the distance to the floor (max 10 blocks)
					int distance = 0;
					for(; distance < 10; distance++) {
						if(server.getWorldChunk(pos).getBlockState(pos).isAir()) {
							pos = pos.down();
						} else {
							break;
						}
					}
					if(server.getWorldChunk(pos).getBlockState(pos).isAir())
						continue;
					// Queue the player to be watched for fall damage
					if(fallingGuys.get(p) == null) {
						DontRunWithKnivesMod.FallGuy fG = new DontRunWithKnivesMod.FallGuy();
						fG.healthBeforeFall = p.getHealth();
						fallingGuys.put(p, fG);
					}
				}
			}

			for(ServerPlayerEntity p : new HashSet<>(fallingGuys.keySet())) {
				BlockPos pos = p.getBlockPos().down();
				if(server.getWorldChunk(pos).getBlockState(pos).isAir())
					continue; // still falling -> ignore

				// Ah, the player is standing on a block again!
				DontRunWithKnivesMod.FallGuy fG = fallingGuys.get(p);
				if(p.getHealth() < fG.healthBeforeFall) {
					final float fallDamageTaken = fG.healthBeforeFall - p.getHealth();

					// Now process both hands of the player and potential damage / fire sources
					float extraDamage = 0;
					float burnTickTime = 0;
					EmotionalDamage main = calcExtraDamage(p.getMainHandStack(), fallDamageTaken);
					extraDamage += main.extraDamage;
					burnTickTime += main.burnTickTime;
					EmotionalDamage off = calcExtraDamage(p.getOffHandStack(), fallDamageTaken);
					extraDamage += off.extraDamage;
					burnTickTime += off.burnTickTime;

					// In case we deal any damage, we'll also burn the player - if necessary
					if (extraDamage > 0) {
						String userMsg = "Don't run with knives! ";
						p.damage(DamageSource.FALL, fallDamageTaken + extraDamage);
						userMsg += "You took " + Math.round(extraDamage) + " HP extra damage";
						if (burnTickTime > 0) {
							p.setOnFireFor(Math.round(burnTickTime  / 20));
							userMsg += " (burning hot!)";
						}
						userMsg += ".";
						p.sendMessage(Text.of(userMsg), true);
					}
				} else {
					// Player is standing on a block, but has not taken any damage (yet)
					if (fG.standingTimeoutTicks > 0) {
						fG.standingTimeoutTicks--;
						continue;
					}
					// If we reach this line, the player has not taken any damage and is not to be watched anymore
				}
				fallingGuys.remove(p);
				break; // Stop processing, as we modified the set we are iterating over -> next tick we'll process the next player (potentially bad with huge servers and many falling players)
			}
		});
	}
}
