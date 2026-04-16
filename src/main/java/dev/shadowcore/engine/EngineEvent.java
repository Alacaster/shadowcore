package dev.shadowcore.engine;

import java.util.UUID;

public sealed interface EngineEvent permits
    EngineEvent.ProfileCreate,
    EngineEvent.ProfileSwitch,
    EngineEvent.ProfileDelete,
    EngineEvent.ProfileReturnToMain,
    EngineEvent.ProfileSetLimit,
    EngineEvent.ProfileAdminDelete,
    EngineEvent.ProfileRename,
    EngineEvent.ShadowMount,
    EngineEvent.ShadowLogout,
    EngineEvent.PlayerJoined,
    EngineEvent.PlayerQuit,
    EngineEvent.PlayerKicked,
    EngineEvent.NameResolved,
    EngineEvent.ShadowNameResolved {

    record ProfileCreate(UUID actor, String actorName, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileSwitch(UUID actor, String actorName, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileDelete(UUID actor, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileReturnToMain(UUID actor, ResponseHandle response) implements EngineEvent {}
    record ProfileSetLimit(UUID admin, UUID target, int limit, ResponseHandle response) implements EngineEvent {}
    record ProfileAdminDelete(UUID admin, UUID owner, String suffix, ResponseHandle response) implements EngineEvent {}
    record ProfileRename(UUID actor, String oldSuffix, String newSuffix, ResponseHandle response) implements EngineEvent {}
    record ShadowMount(UUID actor, String actorName, String targetName, ResponseHandle response) implements EngineEvent {}
    record ShadowLogout(UUID actor, boolean resetLocation, ResponseHandle response) implements EngineEvent {}
    record PlayerJoined(UUID uuid, String name) implements EngineEvent {}
    record PlayerQuit(UUID uuid, String name) implements EngineEvent {}
    record PlayerKicked(UUID uuid, String name) implements EngineEvent {}
    // Async callbacks from Mojang name resolution
    record NameResolved(UUID actor, String actorName, UUID profileUuid, String suffix, boolean isReal, ResponseHandle response) implements EngineEvent {}
    record ShadowNameResolved(UUID actor, String actorName, String targetName, boolean isReal, ResponseHandle response) implements EngineEvent {}
}
