package dev.ftb.mods.ftbquests.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.ftb.mods.ftbquests.quest.team.TeamData;
import dev.ftb.mods.ftbquests.quest.team.TeamManager;
import dev.ftb.mods.ftbquests.util.TeamHttpClient;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public class TeamCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("team")
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> createTeam(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))
                        )
                )
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> invitePlayer(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "player")
                                ))
                        )
                )
                .then(Commands.literal("join")
                        .then(Commands.argument("team_owner", EntityArgument.player())
                                .executes(context -> joinTeam(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "team_owner")
                                ))
                        )
                )
                .then(Commands.literal("leave")
                        .executes(context -> leaveTeam(context.getSource()))
                )
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> kickPlayer(
                                        context.getSource(),
                                        EntityArgument.getPlayer(context, "player")
                                ))
                        )
                )
        );
    }

    private static int createTeam(CommandSourceStack source, String name) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        TeamManager teamManager = TeamManager.getInstance(source.getServer());
        if (teamManager.getPlayerTeam(player.getUUID()) != null) {
            source.sendFailure(Component.literal("You are already in a team."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Creating team..."), false);

        TeamHttpClient.notifyTeamCreated(player.getUUID(), name).thenAccept(success -> {
            player.getServer().execute(() -> {
                if (success) {
                    TeamData team = teamManager.createTeam(name, player.getUUID());
                    if (team != null) {
                        player.sendSystemMessage(Component.literal("Team '" + name + "' created successfully!"));
                    } else {
                        player.sendSystemMessage(Component.literal("Failed to create team locally. Perhaps you joined a team just now?"));
                    }
                } else {
                    player.sendSystemMessage(Component.literal("Failed to create team. The backend service rejected the request."));
                }
            });
        });

        return 1;
    }

    private static int invitePlayer(CommandSourceStack source, ServerPlayer invitedPlayer) {
        ServerPlayer owner;
        try {
            owner = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        TeamManager teamManager = TeamManager.getInstance(source.getServer());
        TeamData team = teamManager.getPlayerTeam(owner.getUUID());

        if (team == null || !team.getRole(owner.getUUID()).isLeader()) {
            source.sendFailure(Component.literal("You are not the leader of a team."));
            return 0;
        }

        if (teamManager.getPlayerTeam(invitedPlayer.getUUID()) != null) {
            source.sendFailure(Component.literal(invitedPlayer.getName().getString() + " is already in a team."));
            return 0;
        }

        teamManager.addInvitation(invitedPlayer.getUUID(), team.getId());
        source.sendSuccess(() -> Component.literal("Invited " + invitedPlayer.getName().getString() + " to the team."), false);
        invitedPlayer.sendSystemMessage(Component.literal("You have been invited to join '" + team.getName() + "'. Type /team join " + owner.getName().getString() + " to accept."));

        return 1;
    }

    private static int joinTeam(CommandSourceStack source, ServerPlayer teamOwner) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        TeamManager teamManager = TeamManager.getInstance(source.getServer());
        UUID teamId = teamManager.getInvitation(player.getUUID());
        TeamData team = teamManager.getTeam(teamId);

        if (team == null || !team.getOwner().equals(teamOwner.getUUID())) {
            source.sendFailure(Component.literal("You don't have an invitation to join this team."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Attempting to join team... Archiving your island..."), false);

        TeamHttpClient.notifyIslandAction(player.getUUID(), "archive").thenAccept(success -> {
            player.getServer().execute(() -> {
                if (success) {
                    teamManager.addPlayerToTeam(player.getUUID(), team);
                    player.sendSystemMessage(Component.literal("Successfully joined team '" + team.getName() + "'!"));
                    teamOwner.sendSystemMessage(Component.literal(player.getName().getString() + " has joined your team."));
                } else {
                    player.sendSystemMessage(Component.literal("Failed to join team: Could not archive your island."));
                }
            });
        });

        return 1;
    }

    private static int leaveTeam(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        TeamManager teamManager = TeamManager.getInstance(source.getServer());
        TeamData team = teamManager.getPlayerTeam(player.getUUID());

        if (team == null) {
            source.sendFailure(Component.literal("You are not in a team."));
            return 0;
        }

        if (team.getOwner().equals(player.getUUID())) {
            source.sendFailure(Component.literal("You are the owner. You must disband the team or transfer ownership. (Disband not implemented yet)"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Leaving team... Restoring your island..."), false);

        TeamHttpClient.notifyIslandAction(player.getUUID(), "restore").thenAccept(success -> {
            player.getServer().execute(() -> {
                if (success) {
                    teamManager.removePlayerFromTeam(player.getUUID());
                    player.sendSystemMessage(Component.literal("You have left the team."));
                } else {
                    player.sendSystemMessage(Component.literal("Failed to leave team: Could not restore your island."));
                }
            });
        });

        return 1;
    }

    private static int kickPlayer(CommandSourceStack source, ServerPlayer playerToKick) {
        ServerPlayer owner;
        try {
            owner = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("This command can only be run by a player."));
            return 0;
        }

        TeamManager teamManager = TeamManager.getInstance(source.getServer());
        TeamData team = teamManager.getPlayerTeam(owner.getUUID());

        if (team == null || !team.getRole(owner.getUUID()).isLeader()) {
            source.sendFailure(Component.literal("You are not the leader of a team."));
            return 0;
        }

        if (!team.isMember(playerToKick.getUUID())) {
            source.sendFailure(Component.literal(playerToKick.getName().getString() + " is not in your team."));
            return 0;
        }
        
        if (owner.getUUID().equals(playerToKick.getUUID())) {
            source.sendFailure(Component.literal("You cannot kick yourself."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Kicking player... Restoring their island..."), false);
        
        TeamHttpClient.notifyIslandAction(playerToKick.getUUID(), "restore").thenAccept(success -> {
            owner.getServer().execute(() -> {
                if (success) {
                    teamManager.removePlayerFromTeam(playerToKick.getUUID());
                    owner.sendSystemMessage(Component.literal("You have kicked " + playerToKick.getName().getString() + " from the team."));
                    playerToKick.sendSystemMessage(Component.literal("You have been kicked from the team '" + team.getName() + "'."));
                } else {
                    owner.sendSystemMessage(Component.literal("Failed to kick player: Could not restore their island."));
                }
            });
        });
        
        return 1;
    }
}
