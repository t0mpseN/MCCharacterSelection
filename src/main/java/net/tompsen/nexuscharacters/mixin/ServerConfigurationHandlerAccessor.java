package net.tompsen.nexuscharacters.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.network.ServerConfigurationNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerConfigurationNetworkHandler.class)
public interface ServerConfigurationHandlerAccessor {
    @Accessor("profile")
    GameProfile getProfile();
}
