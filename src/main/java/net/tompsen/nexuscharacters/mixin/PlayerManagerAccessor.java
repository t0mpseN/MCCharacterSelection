package net.tompsen.nexuscharacters.mixin;

import net.minecraft.advancement.PlayerAdvancementTracker;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.Map;
import java.util.UUID;

@Mixin(PlayerManager.class)
public interface PlayerManagerAccessor {
    @Accessor("advancementTrackers")
    Map<UUID, PlayerAdvancementTracker> getAdvancementTrackers();

    @Accessor("statisticsMap")
    Map<UUID, net.minecraft.stat.ServerStatHandler> getStatHandlers();

    @Invoker("savePlayerData")
    void invokeSavePlayerData(ServerPlayerEntity player);
}