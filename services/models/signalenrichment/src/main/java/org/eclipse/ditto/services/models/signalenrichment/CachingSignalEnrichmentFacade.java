/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.models.signalenrichment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonObjectBuilder;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.policies.PolicyId;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.services.utils.cache.Cache;
import org.eclipse.ditto.services.utils.cache.CacheFactory;
import org.eclipse.ditto.services.utils.cache.EntityIdWithResourceType;
import org.eclipse.ditto.services.utils.cache.config.CacheConfig;
import org.eclipse.ditto.services.utils.cacheloaders.ThingEnrichmentCacheLoader;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.things.ThingCommand;
import org.eclipse.ditto.signals.events.things.ThingEvent;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;

import akka.actor.ActorRef;

/**
 * Retrieve additional parts of things by asking an asynchronous cache.
 */
public final class CachingSignalEnrichmentFacade implements SignalEnrichmentFacade, Consumer<PolicyId> {

    private final Cache<EntityIdWithResourceType, JsonObject> extraFieldsCache;
    private final Map<PolicyId, List<EntityIdWithResourceType>> entitiesWithPolicyId;

    private CachingSignalEnrichmentFacade(
            final ActorRef commandHandler,
            final Duration askTimeout,
            final CacheConfig cacheConfig,
            final Executor cacheLoaderExecutor) {

        final AsyncCacheLoader<EntityIdWithResourceType, JsonObject> thingEnrichmentCacheLoader =
                new ThingEnrichmentCacheLoader(askTimeout, commandHandler);
        extraFieldsCache = CacheFactory.createCache(thingEnrichmentCacheLoader, cacheConfig,
                null, // explicitly disable metrics for this cache
                cacheLoaderExecutor);
        entitiesWithPolicyId = new HashMap<>();
    }

    /**
     * Create a signal-enriching facade that retrieves partial things by using a Caffeine cache.
     *
     * @param commandHandler the actor used to send "retrieve" signals.
     * @param askTimeout How long to wait for the async cache loader when loading enriched things.
     * @param cacheConfig the cache configuration to use for the cache.
     * @param cacheLoaderExecutor the executor to use in order to asynchronously load cache entries.
     * @return The facade.
     * @throws NullPointerException if any argument is null.
     */
    public static CachingSignalEnrichmentFacade of(final ActorRef commandHandler, final Duration askTimeout,
            final CacheConfig cacheConfig, final Executor cacheLoaderExecutor) {
        return new CachingSignalEnrichmentFacade(commandHandler, askTimeout, cacheConfig, cacheLoaderExecutor);
    }

    @Override
    public CompletionStage<JsonObject> retrievePartialThing(final ThingId thingId,
            final JsonFieldSelector jsonFieldSelector, final DittoHeaders dittoHeaders, final Signal concernedSignal) {

        // as second step only return what was originally requested as fields:
        return doRetrievePartialThing(thingId, jsonFieldSelector, dittoHeaders, concernedSignal)
                .thenApply(jsonObject -> jsonObject.get(jsonFieldSelector));
    }

    @Override
    public void accept(final PolicyId policyId) {
        final List<EntityIdWithResourceType> affectedIds = new ArrayList<>(
                Optional.ofNullable(entitiesWithPolicyId.get(policyId))
                        .orElse(Collections.emptyList())
        );

        affectedIds.forEach(entityIdWithResourceType -> {
            extraFieldsCache.invalidate(entityIdWithResourceType);
            entitiesWithPolicyId.get(policyId).remove(entityIdWithResourceType);
        });
    }

    private CompletionStage<JsonObject> doRetrievePartialThing(final ThingId thingId,
            final JsonFieldSelector jsonFieldSelector, final DittoHeaders dittoHeaders, final Signal concernedSignal) {

        final JsonFieldSelector enhancedFieldSelector = JsonFactory.newFieldSelectorBuilder()
                .addPointers(jsonFieldSelector)
                .addFieldDefinition(Thing.JsonFields.POLICY_ID) // additionally always select the policyId
                .addFieldDefinition(Thing.JsonFields.REVISION) // additionally always select the revision
                .build();
        final EntityIdWithResourceType idWithResourceType = EntityIdWithResourceType.of(
                ThingCommand.RESOURCE_TYPE, thingId,
                CacheFactory.newCacheLookupContext(dittoHeaders, enhancedFieldSelector));

        if (concernedSignal instanceof ThingEvent) {
            final ThingEvent<?> thingEvent = (ThingEvent<?>) concernedSignal;
            return smartUpdateCachedObject(enhancedFieldSelector, idWithResourceType, thingEvent);
        }
        return doCacheLookup(idWithResourceType);
    }

    private CompletableFuture<JsonObject> smartUpdateCachedObject(
            final JsonFieldSelector enhancedFieldSelector,
            final EntityIdWithResourceType idWithResourceType,
            final ThingEvent<?> thingEvent) {

        if (thingEvent.getResourcePath().isEmpty()) {
            // a complete Thing was created/modified -> no need to retrieve via cache as all information is there
            return handleFullChangedThingEvent(enhancedFieldSelector, idWithResourceType, thingEvent);
        } else {
            return doCacheLookup(idWithResourceType).thenCompose(cachedJsonObject -> {
                final JsonObjectBuilder jsonObjectBuilder = cachedJsonObject.toBuilder();
                final long cachedRevision = cachedJsonObject.getValue(Thing.JsonFields.REVISION).orElse(0L);
                if (cachedRevision == thingEvent.getRevision()) {
                    // the cache entry was not present before and just loaded
                    return CompletableFuture.completedFuture(cachedJsonObject);
                } else if (cachedRevision + 1 == thingEvent.getRevision()) {
                    // the cache entry was already present and the thingEvent was the next expected revision no
                    // -> we have all information necessary to calculate it without making another roundtrip
                    return handleNextExpectedThingEvent(enhancedFieldSelector, idWithResourceType, thingEvent,
                            jsonObjectBuilder);
                } else {
                    // the cache entry was already present, but we missed sth and need to invalidate the cache
                    // and to another cache lookup (via roundtrip)
                    extraFieldsCache.invalidate(idWithResourceType);
                    return doCacheLookup(idWithResourceType);
                }
            });
        }
    }

    private CompletableFuture<JsonObject> handleFullChangedThingEvent(final JsonFieldSelector enhancedFieldSelector,
            final EntityIdWithResourceType idWithResourceType,
            final ThingEvent<?> thingEvent) {

        final JsonObject derivedObject = thingEvent.getEntity()
                .filter(JsonValue::isObject)
                .map(JsonValue::asObject)
                .map(JsonObject::toBuilder)
                .map(jsonObjectBuilder -> {
                    jsonObjectBuilder.set(Thing.JsonFields.REVISION, thingEvent.getRevision());
                    thingEvent.getTimestamp().ifPresent(ts ->
                            jsonObjectBuilder.set(Thing.JsonFields.MODIFIED, ts.toString())
                    );
                    return jsonObjectBuilder.build();
                })
                .map(jsonObject -> jsonObject.get(enhancedFieldSelector))
                .orElse(null);

        if (null != derivedObject) {
            // add to cache and directly return as result
            extraFieldsCache.put(idWithResourceType, derivedObject);
            updatePolicyIdCache(derivedObject, idWithResourceType);
            return CompletableFuture.completedFuture(derivedObject);
        } else {
            return doCacheLookup(idWithResourceType);
        }
    }

    private CompletionStage<JsonObject> handleNextExpectedThingEvent(final JsonFieldSelector enhancedFieldSelector,
            final EntityIdWithResourceType idWithResourceType, final ThingEvent<?> thingEvent,
            final JsonObjectBuilder jsonObjectBuilder) {

        final JsonPointer resourcePath = thingEvent.getResourcePath();
        if (Thing.JsonFields.POLICY_ID.getPointer().equals(resourcePath)) {
            // invalidate the cache
            extraFieldsCache.invalidate(idWithResourceType);
            // and to another cache lookup (via roundtrip):
            return doCacheLookup(idWithResourceType);
        }
        final Optional<JsonValue> optEntity = thingEvent.getEntity();
        optEntity.ifPresent(entity -> jsonObjectBuilder
                .set(resourcePath.toString(), entity)
                .set(Thing.JsonFields.REVISION, thingEvent.getRevision())
        );
        final JsonObject enhancedJsonObject = jsonObjectBuilder.build().get(enhancedFieldSelector);
        // update local cache with enhanced object:
        extraFieldsCache.put(idWithResourceType, enhancedJsonObject);
        updatePolicyIdCache(enhancedJsonObject, idWithResourceType);
        return CompletableFuture.completedFuture(enhancedJsonObject);
    }

    private CompletableFuture<JsonObject> doCacheLookup(final EntityIdWithResourceType idWithResourceType) {

        return extraFieldsCache.get(idWithResourceType)
                .thenApply(optionalJsonObject -> {
                    optionalJsonObject.ifPresent(jsonObject -> updatePolicyIdCache(jsonObject, idWithResourceType));
                    return optionalJsonObject;
                })
                .thenApply(optionalJsonObject -> optionalJsonObject.orElse(JsonObject.empty()));
    }

    private void updatePolicyIdCache(final JsonObject jsonObject,
            final EntityIdWithResourceType idWithResourceType) {

        final Optional<String> policyIdOpt = jsonObject.getValue(Thing.JsonFields.POLICY_ID);
        policyIdOpt.map(PolicyId::of).ifPresent(policyId -> {
            if (!entitiesWithPolicyId.containsKey(policyId)) {
                entitiesWithPolicyId.put(policyId, new ArrayList<>());
            }
            entitiesWithPolicyId.get(policyId).add(idWithResourceType);
        });
    }

}
