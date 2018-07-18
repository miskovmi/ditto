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
package org.eclipse.ditto.services.utils.protocol;

import org.eclipse.ditto.model.base.headers.HeaderDefinition;
import org.eclipse.ditto.protocoladapter.HeaderPublisher;
import org.eclipse.ditto.protocoladapter.ProtocolAdapter;

/**
 * Interface for loading protocol adapter at runtime.
 */
public abstract class ProtocolAdapterProvider {

    private final ProtocolConfigReader protocolConfigReader;

    /**
     * This constructor is the obligation of all subclasses of {@code ProtocolAdapterProvider}.
     *
     * @param protocolConfigReader the argument.
     */
    public ProtocolAdapterProvider(final ProtocolConfigReader protocolConfigReader) {
        this.protocolConfigReader = protocolConfigReader;
    }

    /**
     * Retrieve the protocol config reader given by creation of this object.
     *
     * @return the protocol config reader.
     */
    protected ProtocolConfigReader protocolConfigReader() {
        return protocolConfigReader;
    }

    /**
     * Create a protocol adapter.
     *
     * @return the protocol adapter.
     */
    public abstract ProtocolAdapter get();

    /**
     * Create a protocol adapter in compatibility mode.
     *
     * @return protocol adapter in compatibility mode.
     */
    public abstract ProtocolAdapter getForCompatibilityMode();

    /**
     * Create a header publisher to filter incoming HTTP headers for the protocol adapter.
     */
    public abstract HeaderPublisher getHttpHeaderPublisher();

    /**
     * Header definition for headers ignored by Ditto.
     */
    protected static final class Ignored implements HeaderDefinition {

        private final String key;

        /**
         * Create an ignored header.
         *
         * @param key the ignored header key.
         */
        public Ignored(final String key) {
            this.key = key;
        }

        @Override
        public String getKey() {
            return key;
        }

        @Override
        public Class getJavaType() {
            return Object.class;
        }

        @Override
        public boolean shouldReadFromExternalHeaders() {
            return false;
        }

        @Override
        public boolean shouldWriteToExternalHeaders() {
            return false;
        }
    }
}
