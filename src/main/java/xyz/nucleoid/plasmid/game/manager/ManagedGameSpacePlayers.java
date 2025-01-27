package xyz.nucleoid.plasmid.game.manager;

import net.minecraft.server.network.ServerPlayerEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.*;
import xyz.nucleoid.plasmid.game.player.MutablePlayerSet;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.isolation.PlayerManagerAccess;

import java.util.*;
import java.util.function.BiConsumer;

public final class ManagedGameSpacePlayers implements GameSpacePlayers {
    private final ManagedGameSpace space;
    final MutablePlayerSet set;
    private final Map<UUID, BiConsumer<ServerPlayerEntity, GameSpace>> leaveHandlers = new HashMap<>();

    ManagedGameSpacePlayers(ManagedGameSpace space) {
        this.space = space;
        this.set = new MutablePlayerSet(space.getServer());
    }

    @Override
    public GameResult screenJoins(Collection<ServerPlayerEntity> players) {
        return this.space.screenJoins(players);
    }

    @Override
    public GameResult offer(OfferContext context) {
        var result = this.attemptOffer(context);

        if (result.isError()) {
            this.attemptGarbageCollection();
        }

        return result;
    }

    private GameResult attemptOffer(OfferContext context) {

        var player = context.player();
        if (this.set.contains(player)) {
            return GameResult.error(GameTexts.Join.alreadyJoined());
        }

        var offer = new PlayerOffer(player);
        var result = this.space.offerPlayer(offer);

        var reject = result.asReject();
        if (reject != null) {
            return GameResult.error(reject.reason());
        }

        var accept = result.asAccept();
        if (accept != null) {
            try {
                var playerManager = (PlayerManagerAccess)this.space.getServer().getPlayerManager();
                if(!this.space.getWorlds().contains(player.getWorld().getRegistryKey()))
                    return GameResult.error(GameTexts.Join.worldNotSet()); //ensure the player is in the correct world
                if(playerManager.plasmid$playerInstanceAlreadyExists(player))
                    return GameResult.error(GameTexts.Join.playerAlreadyExist()); //ensure the player instance we are using is not already in the player manager

                accept.applyAccept(player); //this must set all the player's properties, including world and position
                context.onApply().run(); //in the default implementation, it removes the player from the world where the player was before joining
                accept.applyJoin();
                this.set.add(player);
                this.space.onAddPlayer(player);
                playerManager.plasmid$AddPlayerAndSendDefaultJoinPacket(player, this, context.sendFirstJoinPacket()); //add the player to the player manager and send the default join packet
                this.leaveHandlers.put(player.getUuid(), context.leaveHandler());



                return GameResult.ok();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                return GameResult.error(GameTexts.Join.unexpectedError());
            }
        } else {
            return GameResult.error(GameTexts.Join.genericError());
        }
    }

    void attemptGarbageCollection() {
        if (this.set.isEmpty()) {
            this.space.close(GameCloseReason.GARBAGE_COLLECTED);
        }
    }

    @Override
    public boolean kick(ServerPlayerEntity player) {
        if (this.set.contains(player)) {
            this.space.onPlayerRemove(player);
            this.set.remove(player);
            this.leaveHandlers.remove(player.getUuid()).accept(player, this.space);
            this.attemptGarbageCollection();
            return true;
        } else {
            return false;
        }
    }


    public BiConsumer<ServerPlayerEntity, GameSpace> remove(ServerPlayerEntity player) {
        if (!this.set.contains(player)) {
            return null;
        }
        this.space.onPlayerRemove(player);
        this.set.remove(player);
        var handler = this.leaveHandlers.remove(player.getUuid());
        this.attemptGarbageCollection();

        return handler;
    }

    void clear() {
        this.set.clear();
        this.leaveHandlers.clear();
    }

    @Override
    public boolean contains(UUID id) {
        return this.set.contains(id);
    }

    @Override
    @Nullable
    public ServerPlayerEntity getEntity(UUID id) {
        return this.set.getEntity(id);
    }

    @Override
    public int size() {
        return this.set.size();
    }

    @Override
    public @NotNull Iterator<ServerPlayerEntity> iterator() {
        return this.set.iterator();
    }
}
