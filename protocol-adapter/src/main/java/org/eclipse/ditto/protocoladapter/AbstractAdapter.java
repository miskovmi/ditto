/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.protocoladapter;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.ditto.json.JsonArray;
import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonParseException;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoJsonException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.Jsonifiable;
import org.eclipse.ditto.model.messages.MessageHeaders;
import org.eclipse.ditto.model.things.AccessControlList;
import org.eclipse.ditto.model.things.AccessControlListModelFactory;
import org.eclipse.ditto.model.things.AclEntry;
import org.eclipse.ditto.model.things.Attributes;
import org.eclipse.ditto.model.things.Feature;
import org.eclipse.ditto.model.things.FeatureProperties;
import org.eclipse.ditto.model.things.Features;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingsModelFactory;

/**
 * Abstract implementation of {@link Adapter} to provide common functionality.
 */
abstract class AbstractAdapter<T extends Jsonifiable> implements Adapter<T> {

    private static final int ATTRIBUTE_PATH_LEVEL = 1;
    private static final int FEATURE_PROPERTY_PATH_LEVEL = 3;

    private final Map<String, JsonifiableMapper<T>> mappingStrategies;

    protected AbstractAdapter(final Map<String, JsonifiableMapper<T>> mappingStrategies) {
        this.mappingStrategies = mappingStrategies;
    }

    protected static boolean isCreated(final Adaptable adaptable) {
        return adaptable.getPayload().getStatus()
                .map(HttpStatusCode.CREATED::equals)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static DittoHeaders dittoHeadersFrom(final Adaptable adaptable) {
        return adaptable.getHeaders().orElse(DittoHeaders.empty());
    }

    protected static AuthorizationSubject authorizationSubjectFrom(final Adaptable adaptable) {
        return AuthorizationSubject.newInstance(leafValue(adaptable.getPayload().getPath()));
    }

    protected static JsonFieldSelector selectedFieldsFrom(final Adaptable adaptable) {
        return adaptable.getPayload().getFields().orElse(null);
    }

    protected static String thingIdFrom(final Adaptable adaptable) {
        final TopicPath topicPath = adaptable.getTopicPath();
        return topicPath.getNamespace() + ":" + topicPath.getId();
    }

    protected static Thing thingFrom(final Adaptable adaptable) {
        return adaptable.getPayload().getValue()
                .map(JsonValue::asObject)
                .map(ThingsModelFactory::newThing)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static JsonArray thingsArrayFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .filter(JsonValue::isArray)
                .map(JsonValue::asArray)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static AccessControlList aclFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(JsonValue::asObject)
                .map(AccessControlListModelFactory::newAcl)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static AclEntry aclEntryFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(permissions -> AccessControlListModelFactory
                        .newAclEntry(leafValue(adaptable.getPayload().getPath()), permissions))
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static Attributes attributesFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(JsonValue::asObject)
                .map(ThingsModelFactory::newAttributes)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static JsonPointer attributePointerFrom(final Adaptable adaptable) {
        final JsonPointer path = adaptable.getPayload().getPath();
        return path.getSubPointer(ATTRIBUTE_PATH_LEVEL)
                .orElseThrow(() -> UnknownPathException.newBuilder(path).build());
    }

    protected static JsonValue attributeValueFrom(final Adaptable adaptable) {
        return adaptable.getPayload().getValue().orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static String featureIdFrom(final Adaptable adaptable) {
        final JsonPointer path = adaptable.getPayload().getPath();
        return path.get(1).orElseThrow(() -> UnknownPathException.newBuilder(path).build()).toString();
    }

    protected static Features featuresFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(JsonValue::asObject)
                .map(ThingsModelFactory::newFeatures)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static Feature featureFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(JsonValue::asObject)
                .map(jsonObject -> ThingsModelFactory.newFeatureBuilder(jsonObject)
                        .useId(featureIdFrom(adaptable))
                        .build())
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static FeatureProperties featurePropertiesFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(JsonValue::asObject)
                .map(ThingsModelFactory::newFeatureProperties)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static JsonPointer featurePropertyPointerFrom(final Adaptable adaptable) {
        final JsonPointer path = adaptable.getPayload().getPath();
        return path.getSubPointer(FEATURE_PROPERTY_PATH_LEVEL)
                .orElseThrow(() -> UnknownPathException.newBuilder(path).build());
    }

    protected static JsonValue featurePropertyValueFrom(final Adaptable adaptable) {
        return adaptable.getPayload().getValue().orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static String policyIdFrom(final Adaptable adaptable) {
        return adaptable.getPayload()
                .getValue()
                .map(JsonValue::asString)
                .orElseThrow(() -> JsonParseException.newBuilder().build());
    }

    protected static HttpStatusCode statusCodeFrom(final Adaptable adaptable) {
        return adaptable.getPayload().getStatus().orElse(null);
    }

    protected static String namespaceFrom(final Adaptable adaptable) {
        final String namespace = adaptable.getTopicPath().getNamespace();
        return "_".equals(namespace) ? null : namespace;
    }

    private static String leafValue(final JsonPointer path) {
        return path.getLeaf().orElseThrow(() -> UnknownPathException.newBuilder(path).build()).toString();
    }

    protected static CommandsTopicPathBuilder fromTopicPathBuilderWithChannel(final TopicPathBuilder topicPathBuilder,
            final TopicPath.Channel channel) {
        final CommandsTopicPathBuilder commandsTopicPathBuilder;
        if (channel == TopicPath.Channel.TWIN) {
            commandsTopicPathBuilder = topicPathBuilder.twin().commands();
        } else if (channel == TopicPath.Channel.LIVE) {
            commandsTopicPathBuilder = topicPathBuilder.live().commands();
        } else {
            throw new IllegalArgumentException("Unknown Channel '" + channel + "'");
        }
        return commandsTopicPathBuilder;
    }

    protected abstract String getType(final Adaptable adaptable);

    protected static TopicPath.Action getAction(final TopicPath topicPath) {
        return topicPath.getAction()
                .orElseThrow(() -> new NullPointerException("TopicPath did not contain an Action!"));
    }

    @Override
    public T fromAdaptable(final Adaptable adaptable) {
        final String type = getType(adaptable);
        final JsonifiableMapper<T> jsonifiableMapper = mappingStrategies.get(type);

        if (null == jsonifiableMapper) {
            throw UnknownTopicPathException.newBuilder(adaptable.getTopicPath()).build();
        }

        return DittoJsonException.wrapJsonRuntimeException(() -> jsonifiableMapper.map(adaptable));
    }

    /**
     * Returns the given String {@code s} with an upper case first letter.
     *
     * @param s the String.
     * @return the upper case String.
     */
    protected static String upperCaseFirst(final String s) {
        if (s.isEmpty()) {
            return s;
        }

        final char[] chars = s.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        return new String(chars);
    }

    /**
     * Utility class for matching {@link Payload} path.
     */
    static final class PathMatcher {

        static final Map<String, Pattern> PATTERN = new HashMap<>();

        static {
            PATTERN.put("thing", java.util.regex.Pattern.compile("^/$"));
            PATTERN.put("acl", java.util.regex.Pattern.compile("^/acl$"));
            PATTERN.put("aclEntry", java.util.regex.Pattern.compile("^/acl/[^/]*$"));
            PATTERN.put("policyId", java.util.regex.Pattern.compile("^/policyId$"));
            PATTERN.put("policy", java.util.regex.Pattern.compile("^/_policy"));
            PATTERN.put("policyEntries", java.util.regex.Pattern.compile("^/_policy/entries$"));
            PATTERN.put("policyEntry", java.util.regex.Pattern.compile("^/_policy/entries/.*$"));
            PATTERN.put("policyEntrySubjects", java.util.regex.Pattern.compile("^/_policy/entries/[^/]*/subjects$"));
            PATTERN.put("policyEntrySubject", java.util.regex.Pattern.compile("^/_policy/entries/[^/]*/subjects/.*$"));
            PATTERN.put("policyEntryResources", java.util.regex.Pattern.compile("^/_policy/entries/[^/]*/resources$"));
            PATTERN.put("policyEntryResource", java.util.regex.Pattern.compile("^/_policy/entries/[^/]*/resources/.*$"));
            PATTERN.put("attributes", java.util.regex.Pattern.compile("^/attributes$"));
            PATTERN.put("attribute", java.util.regex.Pattern.compile("^/attributes/.*$"));
            PATTERN.put("features", java.util.regex.Pattern.compile("^/features$"));
            PATTERN.put("feature", java.util.regex.Pattern.compile("^/features/[^/]*$"));
            PATTERN.put("featureProperties", java.util.regex.Pattern.compile("^/features/[^/]*/properties$"));
            PATTERN.put("featureProperty", java.util.regex.Pattern.compile("^/features/[^/]*/properties/.*$"));
        }

        private PathMatcher() {
            throw new AssertionError();
        }

        /**
         * Matches a given {@code path} against known schemes and returns the corresponding entity name.
         *
         * @param path the path to match.
         * @return the entity name which matched.
         * @throws UnknownPathException if {@code path} matched no known scheme.
         */
        static String match(final JsonPointer path) {
            return PATTERN.entrySet().stream()
                    .filter(entry -> entry.getValue().matcher(path.toString()).matches())
                    .findFirst()
                    .map(Map.Entry::getKey)
                    .orElseThrow(() -> UnknownPathException.newBuilder(path).build());
        }

    }

}
