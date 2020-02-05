// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.azure.cosmos;

import com.azure.cosmos.implementation.Document;
import com.azure.cosmos.implementation.ResourceResponse;
import com.azure.cosmos.implementation.Utils;
import org.apache.commons.lang3.StringUtils;

public class CosmosAsyncItemResponse<T> extends CosmosResponse<CosmosItemProperties> {
    private final Class<T> itemClassType;
    private final byte[] responseBodyAsByteArray;
    private T item;

    CosmosAsyncItemResponse(ResourceResponse<Document> response, Class<T> klass) {
        super(response);
        this.itemClassType = klass;
        this.responseBodyAsByteArray = response.getBodyAsByteArray();
    }

    /**
     * Gets the resource .
     *
     * @return the resource
     */
    public T getResource(){
        if (item != null) {
            return item;
        }

        if (this.itemClassType == CosmosItemProperties.class) {
            item = (T) getProperties();
            return item;
        }

        if (item == null) {
            synchronized (this) {
                if (item == null && !Utils.isEmpty(responseBodyAsByteArray)) {
                    item = Utils.parse(responseBodyAsByteArray, itemClassType);
                }
            }
        }

        return item;
    }

    /**
     * Gets the itemProperties
     *
     * @return the itemProperties
     */
    public CosmosItemProperties getProperties() {
        ensureCosmosItemPropertiesInitialized();
        return super.getProperties();
    }

    private void ensureCosmosItemPropertiesInitialized() {
        if (super.getProperties() == null) {
            synchronized (this) {
                if (super.getProperties() == null) {
                    if (Utils.isEmpty(responseBodyAsByteArray)){
                        super.setProperties(null);
                    } else {
                        CosmosItemProperties props = new CosmosItemProperties(responseBodyAsByteArray);
                        super.setProperties(props);
                    }
                }
            }
        }
    }
}
