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
package org.eclipse.ditto.services.gateway.security.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.server.RequestContext;


/**
 * Tests {@link AuthenticationChain}.
 */
@RunWith(MockitoJUnitRunner.class)
public class AuthenticationChainTest {

    @Mock
    private AuthenticationProvider authenticationProviderA;

    @Mock
    private AuthenticationProvider authenticationProviderB;

    @Mock
    private AuthenticationFailureAggregator authenticationFailureAggregator;

    private final Executor messageDispatcher;

    public AuthenticationChainTest() {
        this.messageDispatcher = Executors.newFixedThreadPool(8);
    }

    @Test
    public void getInstanceThrowsIllegalArgumentExceptionWhenProviderListIsEmpty() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () -> AuthenticationChain.getInstance(Collections.emptyList(), authenticationFailureAggregator,
                                messageDispatcher))
                .withMessage("The argument 'authenticationProviders' must not be empty!")
                .withNoCause();
    }

    @Test
    public void isApplicableWhenLastProviderIsValid() {

        final RequestContext requestContextMock = mock(RequestContext.class);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(false);
        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(true);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final boolean applicable = underTest.isApplicable(requestContextMock);

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB).isApplicable(requestContextMock);
        assertThat(applicable).isTrue();
    }

    @Test
    public void isApplicableWhenFirstProviderIsValid() {

        final RequestContext requestContextMock = mock(RequestContext.class);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(true);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final boolean applicable = underTest.isApplicable(requestContextMock);

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB, never()).isApplicable(requestContextMock);
        assertThat(applicable).isTrue();
    }

    @Test
    public void authenticate() throws ExecutionException, InterruptedException {

        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
        final String correlationId = UUID.randomUUID().toString();
        final AuthenticationResult expectedAuthenticationResult =
                DefaultAuthenticationResult.successful(mock(AuthorizationContext.class));
        final CompletableFuture<AuthenticationResult> expectedAuthenticationResultFuture =
                CompletableFuture.completedFuture(expectedAuthenticationResult);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(false);
        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(true);
        when(authenticationProviderB.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(
                expectedAuthenticationResultFuture);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final AuthenticationResult authenticationResult =
                underTest.authenticate(requestContextMock, correlationId).get();

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB).isApplicable(requestContextMock);
        verify(authenticationProviderA, never()).extractAuthentication(requestContextMock, correlationId,
                messageDispatcher);
        verify(authenticationProviderB).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        assertThat(authenticationResult).isEqualTo(expectedAuthenticationResult);
    }

    @Test
    public void authenticateReturnsFirstAuthenticationResultIfMultipleProvidersAreApplicable()
            throws ExecutionException, InterruptedException {

        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
        final String correlationId = UUID.randomUUID().toString();
        final AuthenticationResult expectedAuthenticationResult =
                DefaultAuthenticationResult.successful(mock(AuthorizationContext.class));
        final CompletableFuture<AuthenticationResult> expectedAuthenticationResultFuture =
                CompletableFuture.completedFuture(expectedAuthenticationResult);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(true);
        when(authenticationProviderA.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(
                expectedAuthenticationResultFuture);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final AuthenticationResult authenticationResult =
                underTest.authenticate(requestContextMock, correlationId).get();

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB, never()).isApplicable(requestContextMock);
        verify(authenticationProviderA).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        verify(authenticationProviderB, never()).extractAuthentication(requestContextMock, correlationId,
                messageDispatcher);
        assertThat(authenticationResult).isEqualTo(expectedAuthenticationResult);
    }

    @Test
    public void authenticateReturnsIllegalStateAuthenticationResultIfCalledWhenNoProvidersAreApplicable()
            throws ExecutionException, InterruptedException {
        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
        final String correlationId = UUID.randomUUID().toString();
        final IllegalStateException expectedException = new IllegalStateException("No applicable authentication " +
                "provider was found. Check with 'isApplicable' before calling 'authenticate'.");
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(false);
        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(false);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final AuthenticationResult authenticationResult =
                underTest.authenticate(requestContextMock, correlationId).get();

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB).isApplicable(requestContextMock);
        verify(authenticationProviderA, never()).extractAuthentication(requestContextMock, correlationId,
                messageDispatcher);
        verify(authenticationProviderB, never()).extractAuthentication(requestContextMock, correlationId,
                messageDispatcher);
        assertThat(authenticationResult.isSuccess()).isFalse();
        assertThat(authenticationResult.getReasonOfFailure()).isInstanceOf(expectedException.getClass());
        assertThat(authenticationResult.getReasonOfFailure()).hasMessage(expectedException.getMessage());
        assertThat(authenticationResult.getReasonOfFailure()).hasNoCause();
    }

    @Test
    public void authenticateReturnsFailedAuthenticationResult() throws ExecutionException, InterruptedException {
        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
        final String correlationId = UUID.randomUUID().toString();
        final AuthenticationResult expectedAuthenticationResult =
                DefaultAuthenticationResult.failed(mock(DittoRuntimeException.class));
        final CompletableFuture<AuthenticationResult> expectedAuthenticationResultFuture =
                CompletableFuture.completedFuture(expectedAuthenticationResult);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(true);
        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(false);
        when(authenticationProviderA.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(expectedAuthenticationResultFuture);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final AuthenticationResult authenticationResult =
                underTest.authenticate(requestContextMock, correlationId).get();

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB).isApplicable(requestContextMock);
        verify(authenticationProviderA).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        verify(authenticationProviderB, never()).extractAuthentication(requestContextMock, correlationId,
                messageDispatcher);
        assertThat(authenticationResult).isEqualTo(expectedAuthenticationResult);
    }

    @Test
    public void authenticateReturnsSuccessfulAuthenticationResultIfOneProviderSucceeds()
            throws ExecutionException, InterruptedException {
        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
        final String correlationId = UUID.randomUUID().toString();
        final AuthenticationResult failedAuthenticationResult =
                DefaultAuthenticationResult.failed(mock(DittoRuntimeException.class));
        final CompletableFuture<AuthenticationResult> failedAuthenticationResultFuture =
                CompletableFuture.completedFuture(failedAuthenticationResult);
        final AuthenticationResult expectedAuthenticationResult =
                DefaultAuthenticationResult.successful(mock(AuthorizationContext.class));
        final CompletableFuture<AuthenticationResult> expectedAuthenticationResultFuture =
                CompletableFuture.completedFuture(expectedAuthenticationResult);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(true);
        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(true);
        when(authenticationProviderA.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(failedAuthenticationResultFuture);
        when(authenticationProviderB.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(expectedAuthenticationResultFuture);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final AuthenticationResult authenticationResult =
                underTest.authenticate(requestContextMock, correlationId).get();

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB).isApplicable(requestContextMock);
        verify(authenticationProviderA).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        verify(authenticationProviderB).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        assertThat(authenticationResult).isEqualTo(expectedAuthenticationResult);
    }

//    @Test
//    public void authenticateAggregatesFailureDescriptionsIfMultipleProvidersFail()
//            throws ExecutionException, InterruptedException {
//        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
//        final String correlationId = UUID.randomUUID().toString();
//        final DittoRuntimeException failureA = mock(DittoRuntimeException.class);
//        when(failureA.getDescription()).thenReturn(Optional.of("Do this"));
//        final AuthenticationResult failedAuthenticationResultA =
//                DefaultAuthenticationResult.failed(failureA);
//        final CompletableFuture<AuthenticationResult> failedAuthenticationResultFutureA =
//                CompletableFuture.completedFuture(failedAuthenticationResultA);
//        final DittoRuntimeException failureB = mock(DittoRuntimeException.class);
//        when(failureB.getDescription()).thenReturn(Optional.of("Do that"));
//        final AuthenticationResult failedAuthenticationResultB =
//                DefaultAuthenticationResult.failed(failureB);
//        final CompletableFuture<AuthenticationResult> expectedAuthenticationResultFuture =
//                CompletableFuture.completedFuture(failedAuthenticationResultB);
//        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(true);
//        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(true);
//        when(authenticationProviderA.extractAuthentication(requestContextMock, correlationId,
//                messageDispatcher)).thenReturn(failedAuthenticationResultFutureA);
//        when(authenticationProviderB.extractAuthentication(requestContextMock, correlationId,
//                messageDispatcher)).thenReturn(expectedAuthenticationResultFuture);
//        final AuthenticationChain underTest =
//                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
//                        authenticationFailureAggregator,
//                        messageDispatcher);
//
//        final AuthenticationResult authenticationResult =
//                underTest.authenticate(requestContextMock, correlationId).get();
//
//        verify(authenticationProviderA).isApplicable(requestContextMock);
//        verify(authenticationProviderB).isApplicable(requestContextMock);
//        verify(authenticationProviderA).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
//        verify(authenticationProviderB).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
//        assertThat(authenticationResult.isSuccess()).isFalse();
//        assertThat(authenticationResult.getReasonOfFailure()).isInstanceOf(GatewayAuthenticationFailedException.class);
//        assertThat(authenticationResult.getReasonOfFailure()).hasMessage(
//                "Multiple authentication mechanisms were applicable but none succeeded.");
//        assertThat(((DittoRuntimeException) authenticationResult.getReasonOfFailure()).getDescription()).contains(
//                "For a successful authentication see the following suggestions: { Do this }, { Do that }.");
//    }

    @Test
    public void authenticateAggregatesFailureDescriptionsIfMultipleProvidersFail()
            throws ExecutionException, InterruptedException {
        final RequestContext requestContextMock = mockRequestContextForAuthenticate();
        final String correlationId = UUID.randomUUID().toString();

        // Failure A
        final DittoRuntimeException failureA = mock(DittoRuntimeException.class);
        final AuthenticationResult failedAuthenticationResultA = DefaultAuthenticationResult.failed(failureA);
        final CompletableFuture<AuthenticationResult> failedAuthenticationResultFutureA =
                CompletableFuture.completedFuture(failedAuthenticationResultA);
        when(authenticationProviderA.isApplicable(requestContextMock)).thenReturn(true);
        when(authenticationProviderA.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(failedAuthenticationResultFutureA);
        // Failure B
        final DittoRuntimeException failureB = mock(DittoRuntimeException.class);
        final AuthenticationResult failedAuthenticationResultB = DefaultAuthenticationResult.failed(failureB);
        final CompletableFuture<AuthenticationResult> failedAuthenticationResultFutureB =
                CompletableFuture.completedFuture(failedAuthenticationResultB);
        when(authenticationProviderB.isApplicable(requestContextMock)).thenReturn(true);

        when(authenticationProviderB.extractAuthentication(requestContextMock, correlationId,
                messageDispatcher)).thenReturn(failedAuthenticationResultFutureB);
        final AuthenticationChain underTest =
                AuthenticationChain.getInstance(Arrays.asList(authenticationProviderA, authenticationProviderB),
                        authenticationFailureAggregator,
                        messageDispatcher);

        final List<AuthenticationResult> failedAuthenticationResult =
                Arrays.asList(failedAuthenticationResultA, failedAuthenticationResultB);
        when(authenticationFailureAggregator.aggregateAuthenticationFailures(failedAuthenticationResult))
                .thenReturn(DittoRuntimeException
                        .newBuilder("test:exception", HttpStatusCode.UNAUTHORIZED)
                        .build());

        final AuthenticationResult authenticationResult =
                underTest.authenticate(requestContextMock, correlationId).get();

        verify(authenticationProviderA).isApplicable(requestContextMock);
        verify(authenticationProviderB).isApplicable(requestContextMock);
        verify(authenticationProviderA).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        verify(authenticationProviderB).extractAuthentication(requestContextMock, correlationId, messageDispatcher);
        verify(authenticationFailureAggregator).aggregateAuthenticationFailures(failedAuthenticationResult);
        assertThat(authenticationResult.isSuccess()).isFalse();
    }

    private RequestContext mockRequestContextForAuthenticate() {
        final HttpRequest httpRequest = mock(HttpRequest.class);
        final RequestContext requestContext = mock(RequestContext.class);
        final Uri expectedUri = Uri.create("https://test.org");
        when(httpRequest.getUri()).thenReturn(expectedUri);
        when(requestContext.getRequest()).thenReturn(httpRequest);

        return requestContext;
    }
}