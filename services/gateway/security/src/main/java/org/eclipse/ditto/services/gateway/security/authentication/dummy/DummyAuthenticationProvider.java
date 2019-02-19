/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.gateway.security.authentication.dummy;

import static org.eclipse.ditto.services.gateway.security.utils.HttpUtils.getRequestHeader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.auth.AuthorizationModelFactory;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.services.gateway.security.HttpHeader;
import org.eclipse.ditto.services.gateway.security.authentication.DefaultAuthenticationResult;
import org.eclipse.ditto.services.gateway.security.authentication.TimeMeasuringAuthenticationProvider;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.signals.commands.base.exceptions.GatewayAuthenticationFailedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import akka.http.javadsl.server.RequestContext;

@Immutable
public final class DummyAuthenticationProvider extends
        TimeMeasuringAuthenticationProvider<DefaultAuthenticationResult> {

    private static final Logger LOGGER = LoggerFactory.getLogger(DummyAuthenticationProvider.class);

    private static final String AUTH_TYPE = "dummy";

    private static final DummyAuthenticationProvider instance = new DummyAuthenticationProvider();

    private DummyAuthenticationProvider() {
        //No Op
    }

    public static DummyAuthenticationProvider getInstance() {
        return instance;
    }

    @Override
    public boolean isApplicable(final RequestContext requestContext) {
        return getRequestHeader(requestContext, HttpHeader.X_DITTO_DUMMY_AUTH.getName()).isPresent() ||
                requestContext.getRequest().getUri().query().get(HttpHeader.X_DITTO_DUMMY_AUTH.getName()).isPresent();
    }

    @Override
    protected CompletableFuture<DefaultAuthenticationResult> doExtractAuthentication(
            final RequestContext requestContext,
            final String correlationId,
            final Executor blockingDispatcher) {

        return CompletableFuture
                .runAsync(() -> LogUtil.enhanceLogWithCorrelationId(correlationId), blockingDispatcher)
                .thenApply(voidValue -> doExtractAuthentication(requestContext, correlationId));
    }

    @Override
    protected DefaultAuthenticationResult toFailedAuthenticationResult(final Throwable throwable,
            final String correlationId) {
        return DefaultAuthenticationResult.failed(toDittoRuntimeException(throwable, correlationId));
    }

    @Override
    public String getType() {
        return AUTH_TYPE;
    }

    private DefaultAuthenticationResult doExtractAuthentication(final RequestContext requestContext,
            final String correlationId) {
        final Optional<String> dummyAuthOpt = getDummyAuth(requestContext);

        if (!dummyAuthOpt.isPresent()) {
            return DefaultAuthenticationResult.failed(buildNotApplicableException(correlationId));
        }

        final String dummyAuth = dummyAuthOpt.get();

        final List<AuthorizationSubject> authorizationSubjects = extractAuthorizationSubjects(dummyAuth);
        if (authorizationSubjects.isEmpty()) {
            return toFailedAuthenticationResult(
                    buildFailedToExtractAuthorizationSubjectsException(dummyAuth, correlationId), correlationId);
        }

        final AuthorizationContext authorizationContext =
                AuthorizationModelFactory.newAuthContext(authorizationSubjects);


        LOGGER.warn("Dummy authentication has been applied for the following subjects: {}", dummyAuth);

        return DefaultAuthenticationResult.successful(authorizationContext);
    }

    private Optional<String> getDummyAuth(final RequestContext requestContext) {
        final Optional<String> dummyAuthFromRequestHeader =
                getRequestHeader(requestContext, HttpHeader.X_DITTO_DUMMY_AUTH.getName());

        if (dummyAuthFromRequestHeader.isPresent()) {
            return dummyAuthFromRequestHeader;
        }

        return requestContext.getRequest().getUri().query().get(HttpHeader.X_DITTO_DUMMY_AUTH.getName());
    }

    private List<AuthorizationSubject> extractAuthorizationSubjects(final String subjectsCommaSeparated) {
        if (subjectsCommaSeparated != null && !subjectsCommaSeparated.isEmpty()) {
            return Arrays.stream(subjectsCommaSeparated.split(",")) //
                    .map(AuthorizationModelFactory::newAuthSubject) //
                    .collect(Collectors.toList());
        } else {
            return Collections.emptyList();
        }
    }

    private DittoRuntimeException buildFailedToExtractAuthorizationSubjectsException(final String dummyAuth,
            final String correlationId) {
        return GatewayAuthenticationFailedException.newBuilder(
                "Failed to extract AuthorizationSubjects from " + HttpHeader.X_DITTO_DUMMY_AUTH.getName() +
                        " header value '" + dummyAuth + "'.")
                .dittoHeaders(DittoHeaders.newBuilder().correlationId(correlationId).build())
                .build();
    }

    private DittoRuntimeException buildNotApplicableException(final String correlationId) {
        return GatewayAuthenticationFailedException.newBuilder("No Dummy authentication was provided.")
                .dittoHeaders(DittoHeaders.newBuilder().correlationId(correlationId).build())
                .build();
    }
}
