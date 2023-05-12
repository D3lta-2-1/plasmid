package xyz.nucleoid.plasmid.game.player;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import xyz.nucleoid.plasmid.event.GameEvents;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.GameSpacePlayers;
import xyz.nucleoid.plasmid.game.GameTexts;
import xyz.nucleoid.plasmid.game.player.isolation.PlayerManagerAccess;
import xyz.nucleoid.plasmid.mixin.game.space.PlayerEntityAccessor;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Utility class for joining players to a {@link GameSpace}. This handles all logic such as collecting all party
 * members, screening, and offering players to the {@link GameSpace}.
 */
public final class GamePlayerJoiner {
    public static Results tryJoin(ServerPlayerEntity player, GameSpace gameSpace) {
        try {
            var players = collectPlayersForJoin(player, gameSpace);
            return tryJoinAll(players, gameSpace);
        } catch (Throwable throwable) {
            return handleJoinException(throwable);
        }
    }

    private static Set<ServerPlayerEntity> collectPlayersForJoin(ServerPlayerEntity player, GameSpace gameSpace) {
        Set<ServerPlayerEntity> players = new ReferenceOpenHashSet<>();
        players.add(player);

        GameEvents.COLLECT_PLAYERS_FOR_JOIN.invoker().collectPlayersForJoin(gameSpace, player, players);

        return players;
    }

    private static Results tryJoinAll(Collection<ServerPlayerEntity> players, GameSpace gameSpace) {
        var results = new Results();

        var screenResult = gameSpace.getPlayers().screenJoins(players);
        if (screenResult.isError()) {
            results.globalError = screenResult.error();
            return results;
        }

        for (var player : players) {
            var result = gameSpace.getPlayers().offer( getContext(player) );
            if (result.isError()) {
                results.playerErrors.put(player, result.error());
            }
        }

        return results;
    }

    public static GameSpacePlayers.OfferContext getContext(ServerPlayerEntity actualPlayer) {
        var MODEL_PARTS = ((PlayerEntityAccessor)actualPlayer).playerModelParts();
        var newPlayer = new ServerPlayerEntity(actualPlayer.server, actualPlayer.getWorld(), actualPlayer.getGameProfile());
        var playerManager = (PlayerManagerAccess) Objects.requireNonNull(actualPlayer.getServer()).getPlayerManager();

        return new GameSpacePlayers.OfferContext(newPlayer,
                () -> { //executed when the player joins the game space
                    playerManager.plasmid$savePlayerData(actualPlayer); //save the player data
                    playerManager.plasmid$removePlayer(actualPlayer);

                    var handler = actualPlayer.networkHandler;
                    handler.player = newPlayer; //change the player in the network handler
                    newPlayer.networkHandler = handler; //copy the network handler

                    newPlayer.setId(actualPlayer.getId()); //copy the id
                    newPlayer.setMainArm(actualPlayer.getMainArm()); //copy the main arm

                    newPlayer.getDataTracker().set(MODEL_PARTS, actualPlayer.getDataTracker().get(MODEL_PARTS)); //copy skin layers

            }, false,


                (oldPlayer) -> { //executed when the player leaves the game space
                    playerManager.plasmid$removePlayer(oldPlayer);
                    actualPlayer.unsetRemoved();

                    var handler = oldPlayer.networkHandler; //reset the network handler for the old player
                    handler.player = actualPlayer; //change the player in the network handler
                    actualPlayer.networkHandler = handler; //copy the network handler

                    actualPlayer.getDataTracker().set(MODEL_PARTS, oldPlayer.getDataTracker().get(MODEL_PARTS), true); //copy skin layers, true to make it dirty and force a sync
                    playerManager.plasmid$AddPlayerAndSendDefaultJoinPacket(actualPlayer,false);
        });
    }

    public static Results handleJoinException(Throwable throwable) {
        var results = new Results();
        results.globalError = getFeedbackForException(throwable);
        return results;
    }

    private static Text getFeedbackForException(Throwable throwable) {
        var gameOpenException = GameOpenException.unwrap(throwable);
        if (gameOpenException != null) {
            return gameOpenException.getReason().copy();
        } else {
            return GameTexts.Join.unexpectedError();
        }
    }

    public static final class Results {
        public Text globalError;
        public final Map<ServerPlayerEntity, Text> playerErrors = new Reference2ObjectOpenHashMap<>();
        public void sendErrorsTo(ServerPlayerEntity player) {
            if (this.globalError != null) {
                player.sendMessage(this.globalError.copy().formatted(Formatting.RED), false);
            } else if (!this.playerErrors.isEmpty()) {
                player.sendMessage(
                        GameTexts.Join.partyJoinError(this.playerErrors.size()).formatted(Formatting.RED),
                        false
                );

                for (var entry : this.playerErrors.entrySet()) {
                    Text error = entry.getValue().copy().formatted(Formatting.RED);
                    entry.getKey().sendMessage(error, false);
                }
            }
        }
    }
}
