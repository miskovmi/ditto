/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.things.persistence.actors.strategies.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.ditto.model.things.TestConstants.Thing.THING_V2;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.headers.metadata.MetadataHeaderKey;
import org.eclipse.ditto.model.things.TestConstants;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.services.utils.persistentactors.commands.CommandStrategy;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeature;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeatureResponse;
import org.eclipse.ditto.signals.events.things.FeatureDeleted;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit test for {@link DeleteFeatureStrategy}.
 */
public final class DeleteFeatureStrategyTest extends AbstractCommandStrategyTest {

    private static String featureId;

    private DeleteFeatureStrategy underTest;

    @BeforeClass
    public static void initTestFixture() {
        featureId = TestConstants.Feature.FLUX_CAPACITOR_ID;
    }

    @Before
    public void setUp() {
        underTest = new DeleteFeatureStrategy();
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(DeleteFeatureStrategy.class, areImmutable());
    }

    @Test
    public void successfullyDeleteFeatureFromThing() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeature command = DeleteFeature.of(context.getState(), featureId, DittoHeaders.newBuilder()
                // metadata is ignored
                .putMetadata(MetadataHeaderKey.of(JsonPointer.of("sn")), JsonValue.of(2))
                .build());

        final FeatureDeleted event = assertModificationResult(underTest, THING_V2, command, FeatureDeleted.class,
                DeleteFeatureResponse.of(context.getState(), command.getFeatureId(), command.getDittoHeaders()));

        assertThat(event.getMetadata()).isEmpty();
    }

    @Test
    public void deleteFeatureFromThingWithoutFeatures() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeature command = DeleteFeature.of(context.getState(), featureId, DittoHeaders.empty());
        final DittoRuntimeException expectedException =
                ExceptionFactory.featureNotFound(context.getState(), command.getFeatureId(),
                        command.getDittoHeaders());

        assertErrorResult(underTest, THING_V2.removeFeatures(), command, expectedException);
    }

    @Test
    public void deleteFeatureFromThingWithoutThatFeature() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeature command = DeleteFeature.of(context.getState(), "myFeature", DittoHeaders.empty());
        final DittoRuntimeException expectedException =
                ExceptionFactory.featureNotFound(context.getState(), command.getFeatureId(),
                        command.getDittoHeaders());

        assertErrorResult(underTest, THING_V2, command, expectedException);
    }

}
