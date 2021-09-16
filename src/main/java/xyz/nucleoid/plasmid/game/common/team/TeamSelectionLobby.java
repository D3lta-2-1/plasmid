package xyz.nucleoid.plasmid.game.common.team;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.tag.ItemTags;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import xyz.nucleoid.plasmid.Plasmid;
import xyz.nucleoid.plasmid.game.GameActivity;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.util.ColoredBlocks;
import xyz.nucleoid.stimuli.event.item.ItemUseEvent;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * A very simple team selection lobby implementation that allows players to select a team while waiting to start a game.
 * <p>
 * This makes use of {@link TeamAllocator} in order to assign players teams fairly and take into account maximum team
 * sizes as well as team preferences.
 *
 * @see TeamSelectionLobby#allocate(BiConsumer)
 * @see GameTeam
 * @see TeamAllocator
 * @see xyz.nucleoid.plasmid.game.common.GameWaitingLobby
 */
public final class TeamSelectionLobby {
    private static final String TEAM_KEY = Plasmid.ID + ":team";

    private final GameSpace gameSpace;
    private final Map<String, GameTeam> teams;

    private final Reference2IntMap<GameTeam> maxTeamSize = new Reference2IntOpenHashMap<>();
    private final Map<UUID, GameTeam> teamPreference = new Object2ObjectOpenHashMap<>();

    private TeamSelectionLobby(GameSpace gameSpace, Map<String, GameTeam> teams) {
        this.gameSpace = gameSpace;
        this.teams = teams;
    }

    /**
     * Sets the maximum number of players that can be allocated to the given team.
     *
     * @param team the team to set a maximum size for
     * @param size the maximum number of players that can be allocated
     */
    public void setSizeForTeam(GameTeam team, int size) {
        this.maxTeamSize.put(team, size);
    }

    /**
     * Applies this team selection lobby implementation to the given {@link GameActivity} with the given teams.
     *
     * @param activity the activity to apply this lobby to
     * @param teams the teams to allow players to select
     * @return a {@link TeamSelectionLobby} instance which should be used to extract allocated team data
     */
    public static TeamSelectionLobby addTo(GameActivity activity, Collection<GameTeam> teams) {
        var teamMap = new Object2ObjectOpenHashMap<String, GameTeam>();
        for (GameTeam team : teams) {
            teamMap.put(team.key(), team);
        }

        var lobby = new TeamSelectionLobby(activity.getGameSpace(), teamMap);

        activity.listen(GamePlayerEvents.ADD, lobby::onAddPlayer);
        activity.listen(ItemUseEvent.EVENT, lobby::onUseItem);

        return lobby;
    }

    private void onAddPlayer(ServerPlayerEntity player) {
        int index = 0;

        for (var team : this.teams.values()) {
            var name = new TranslatableText("text.plasmid.team_selection.request_team", team.display())
                    .formatted(Formatting.BOLD, team.formatting());

            var stack = new ItemStack(ColoredBlocks.wool(team.blockDyeColor()));
            stack.setCustomName(name);

            stack.getOrCreateTag().putString(TEAM_KEY, team.key());

            player.getInventory().setStack(index++, stack);
        }
    }

    private TypedActionResult<ItemStack> onUseItem(ServerPlayerEntity player, Hand hand) {
        var stack = player.getStackInHand(hand);

        if (stack.isIn(ItemTags.WOOL)) {
            var tag = stack.getOrCreateTag();
            var teamKey = tag.getString(TEAM_KEY);

            var team = this.teams.get(teamKey);
            if (team != null) {
                this.teamPreference.put(player.getUuid(), team);

                var message = new TranslatableText("text.plasmid.team_selection.requested_team",
                        new TranslatableText("text.plasmid.team_selection.suffixed_team", team.display()).formatted(team.formatting()));

                player.sendMessage(message, false);

                return TypedActionResult.success(stack);
            }
        }

        return TypedActionResult.pass(ItemStack.EMPTY);
    }

    /**
     * Allocates all the players within this lobby into teams depending on their specified preferences.
     *
     * @param apply a consumer that accepts each player and their corresponding team
     * @see TeamAllocator
     */
    public void allocate(BiConsumer<GameTeam, ServerPlayerEntity> apply) {
        var allocator = new TeamAllocator<GameTeam, ServerPlayerEntity>(this.teams.values());

        for (var entry : Reference2IntMaps.fastIterable(this.maxTeamSize)) {
            allocator.setSizeForTeam(entry.getKey(), entry.getIntValue());
        }

        for (var player : this.gameSpace.getPlayers()) {
            GameTeam preference = this.teamPreference.get(player.getUuid());
            allocator.add(player, preference);
        }

        allocator.allocate(apply);
    }
}