/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 *
 * Code generated by Microsoft (R) AutoRest Code Generator.
 */

package com.microsoft.azure.management.sql.v2017_03_01_preview.implementation;

import com.microsoft.azure.management.sql.v2017_03_01_preview.Sku;
import com.microsoft.azure.management.sql.v2017_03_01_preview.JobAgentState;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.rest.serializer.JsonFlatten;
import com.microsoft.azure.Resource;

/**
 * An Azure SQL job agent.
 */
@JsonFlatten
public class JobAgentInner extends Resource {
    /**
     * The name and tier of the SKU.
     */
    @JsonProperty(value = "sku")
    private Sku sku;

    /**
     * Resource ID of the database to store job metadata in.
     */
    @JsonProperty(value = "properties.databaseId", required = true)
    private String databaseId;

    /**
     * The state of the job agent. Possible values include: 'Creating',
     * 'Ready', 'Updating', 'Deleting', 'Disabled'.
     */
    @JsonProperty(value = "properties.state", access = JsonProperty.Access.WRITE_ONLY)
    private JobAgentState state;

    /**
     * Get the name and tier of the SKU.
     *
     * @return the sku value
     */
    public Sku sku() {
        return this.sku;
    }

    /**
     * Set the name and tier of the SKU.
     *
     * @param sku the sku value to set
     * @return the JobAgentInner object itself.
     */
    public JobAgentInner withSku(Sku sku) {
        this.sku = sku;
        return this;
    }

    /**
     * Get resource ID of the database to store job metadata in.
     *
     * @return the databaseId value
     */
    public String databaseId() {
        return this.databaseId;
    }

    /**
     * Set resource ID of the database to store job metadata in.
     *
     * @param databaseId the databaseId value to set
     * @return the JobAgentInner object itself.
     */
    public JobAgentInner withDatabaseId(String databaseId) {
        this.databaseId = databaseId;
        return this;
    }

    /**
     * Get the state of the job agent. Possible values include: 'Creating', 'Ready', 'Updating', 'Deleting', 'Disabled'.
     *
     * @return the state value
     */
    public JobAgentState state() {
        return this.state;
    }

}
