// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.azure.cosmos.implementation;

import com.azure.cosmos.CosmosItemProperties;
import com.azure.cosmos.implementation.caches.RxClientCollectionCache;
import com.azure.cosmos.implementation.caches.RxCollectionCache;
import com.azure.cosmos.implementation.caches.RxPartitionKeyRangeCache;
import com.azure.cosmos.implementation.directconnectivity.GatewayServiceConfigurationReader;
import com.azure.cosmos.implementation.directconnectivity.GlobalAddressResolver;
import com.azure.cosmos.implementation.directconnectivity.ServerStoreModel;
import com.azure.cosmos.implementation.directconnectivity.StoreClient;
import com.azure.cosmos.implementation.directconnectivity.StoreClientFactory;
import com.azure.cosmos.implementation.http.HttpClient;
import com.azure.cosmos.implementation.http.HttpClientConfig;
import com.azure.cosmos.implementation.query.DocumentQueryExecutionContextFactory;
import com.azure.cosmos.implementation.query.IDocumentQueryClient;
import com.azure.cosmos.implementation.query.IDocumentQueryExecutionContext;
import com.azure.cosmos.implementation.query.Paginator;
import com.azure.cosmos.implementation.routing.PartitionKeyAndResourceTokenPair;
import com.azure.cosmos.implementation.routing.PartitionKeyInternal;
import com.azure.cosmos.AccessConditionType;
import com.azure.cosmos.BridgeInternal;
import com.azure.cosmos.ChangeFeedOptions;
import com.azure.cosmos.ConnectionMode;
import com.azure.cosmos.ConnectionPolicy;
import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosKeyCredential;
import com.azure.cosmos.CosmosResourceType;
import com.azure.cosmos.DatabaseAccount;
import com.azure.cosmos.FeedOptions;
import com.azure.cosmos.FeedResponse;
import com.azure.cosmos.JsonSerializable;
import com.azure.cosmos.PartitionKey;
import com.azure.cosmos.PartitionKeyDefinition;
import com.azure.cosmos.Resource;
import com.azure.cosmos.SqlQuerySpec;
import com.azure.cosmos.TokenResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.azure.cosmos.BridgeInternal.documentFromObject;
import static com.azure.cosmos.BridgeInternal.getAltLink;
import static com.azure.cosmos.BridgeInternal.serializeJsonToByteBuffer;
import static com.azure.cosmos.BridgeInternal.toDatabaseAccount;
import static com.azure.cosmos.BridgeInternal.toFeedResponsePage;
import static com.azure.cosmos.BridgeInternal.toJsonString;
import static com.azure.cosmos.BridgeInternal.toResourceResponse;
import static com.azure.cosmos.BridgeInternal.toStoredProcedureResponse;

/**
 * While this class is public, but it is not part of our published public APIs.
 * This is meant to be internally used only by our sdk.
 */
public class RxDocumentClientImpl implements AsyncDocumentClient, IAuthorizationTokenProvider {
    private final static ObjectMapper mapper = Utils.getSimpleObjectMapper();
    private final Logger logger = LoggerFactory.getLogger(RxDocumentClientImpl.class);
    private final String masterKeyOrResourceToken;
    private final URI serviceEndpoint;
    private final ConnectionPolicy connectionPolicy;
    private final ConsistencyLevel consistencyLevel;
    private final BaseAuthorizationTokenProvider authorizationTokenProvider;
    private final UserAgentContainer userAgentContainer;
    private final boolean hasAuthKeyResourceToken;
    private final Configs configs;
    private CosmosKeyCredential cosmosKeyCredential;
    private TokenResolver tokenResolver;
    private SessionContainer sessionContainer;
    private String firstResourceTokenFromPermissionFeed = StringUtils.EMPTY;
    private RxClientCollectionCache collectionCache;
    private RxStoreModel gatewayProxy;
    private RxStoreModel storeModel;
    private GlobalAddressResolver addressResolver;
    private RxPartitionKeyRangeCache partitionKeyRangeCache;
    private Map<String, List<PartitionKeyAndResourceTokenPair>> resourceTokensMap;

    // RetryPolicy retries a request when it encounters session unavailable (see ClientRetryPolicy).
    // Once it exhausts all write regions it clears the session container, then it uses RxClientCollectionCache
    // to resolves the request's collection name. If it differs from the session container's resource id it
    // explains the session unavailable exception: somebody removed and recreated the collection. In this
    // case we retry once again (with empty session token) otherwise we return the error to the client
    // (see RenameCollectionAwareClientRetryPolicy)
    private IRetryPolicyFactory resetSessionTokenRetryPolicy;
    /**
     * Compatibility mode: Allows to specify compatibility mode used by client when
     * making query requests. Should be removed when application/sql is no longer
     * supported.
     */
    private final QueryCompatibilityMode queryCompatibilityMode = QueryCompatibilityMode.Default;
    private final HttpClient reactorHttpClient;
    private final GlobalEndpointManager globalEndpointManager;
    private final RetryPolicy retryPolicy;
    private volatile boolean useMultipleWriteLocations;

    // creator of TransportClient is responsible for disposing it.
    private StoreClientFactory storeClientFactory;

    private GatewayServiceConfigurationReader gatewayConfigurationReader;

    public RxDocumentClientImpl(URI serviceEndpoint,
                                String masterKeyOrResourceToken,
                                List<Permission> permissionFeed,
                                ConnectionPolicy connectionPolicy,
                                ConsistencyLevel consistencyLevel,
                                Configs configs,
                                TokenResolver tokenResolver,
                                CosmosKeyCredential cosmosKeyCredential) {
        this(serviceEndpoint, masterKeyOrResourceToken, permissionFeed, connectionPolicy, consistencyLevel, configs, cosmosKeyCredential);
        this.tokenResolver = tokenResolver;
    }

    private RxDocumentClientImpl(URI serviceEndpoint,
                                String masterKeyOrResourceToken,
                                List<Permission> permissionFeed,
                                ConnectionPolicy connectionPolicy,
                                ConsistencyLevel consistencyLevel,
                                Configs configs,
                                CosmosKeyCredential cosmosKeyCredential) {
        this(serviceEndpoint, masterKeyOrResourceToken, connectionPolicy, consistencyLevel, configs, cosmosKeyCredential);
        if (permissionFeed != null && permissionFeed.size() > 0) {
            this.resourceTokensMap = new HashMap<>();
            for (Permission permission : permissionFeed) {
                String[] segments = StringUtils.split(permission.getResourceLink(),
                        Constants.Properties.PATH_SEPARATOR.charAt(0));

                if (segments.length <= 0) {
                    throw new IllegalArgumentException("resourceLink");
                }

                List<PartitionKeyAndResourceTokenPair> partitionKeyAndResourceTokenPairs = null;
                PathInfo pathInfo = new PathInfo(false, StringUtils.EMPTY, StringUtils.EMPTY, false);
                if (!PathsHelper.tryParsePathSegments(permission.getResourceLink(), pathInfo, null)) {
                    throw new IllegalArgumentException(permission.getResourceLink());
                }

                partitionKeyAndResourceTokenPairs = resourceTokensMap.get(pathInfo.resourceIdOrFullName);
                if (partitionKeyAndResourceTokenPairs == null) {
                    partitionKeyAndResourceTokenPairs = new ArrayList<>();
                    this.resourceTokensMap.put(pathInfo.resourceIdOrFullName, partitionKeyAndResourceTokenPairs);
                }

                PartitionKey partitionKey = permission.getResourcePartitionKey();
                partitionKeyAndResourceTokenPairs.add(new PartitionKeyAndResourceTokenPair(
                        partitionKey != null ? BridgeInternal.getPartitionKeyInternal(partitionKey) : PartitionKeyInternal.Empty,
                        permission.getToken()));
                logger.debug("Initializing resource token map  , with map key [{}] , partition key [{}] and resource token",
                        pathInfo.resourceIdOrFullName, partitionKey != null ? partitionKey.toString() : null, permission.getToken());

            }

            if(this.resourceTokensMap.isEmpty()) {
                throw new IllegalArgumentException("permissionFeed");
            }

            String firstToken = permissionFeed.get(0).getToken();
            if(ResourceTokenAuthorizationHelper.isResourceToken(firstToken)) {
                this.firstResourceTokenFromPermissionFeed = firstToken;
            }
        }
    }

    RxDocumentClientImpl(URI serviceEndpoint, String masterKeyOrResourceToken, ConnectionPolicy connectionPolicy,
                         ConsistencyLevel consistencyLevel, Configs configs, CosmosKeyCredential cosmosKeyCredential) {

        logger.info(
            "Initializing DocumentClient with"
                + " serviceEndpoint [{}], connectionPolicy [{}], consistencyLevel [{}], directModeProtocol [{}]",
            serviceEndpoint, connectionPolicy, consistencyLevel, configs.getProtocol());

        this.configs = configs;
        this.masterKeyOrResourceToken = masterKeyOrResourceToken;
        this.serviceEndpoint = serviceEndpoint;
        this.cosmosKeyCredential = cosmosKeyCredential;

        if (this.cosmosKeyCredential != null) {
            hasAuthKeyResourceToken = false;
            this.authorizationTokenProvider = new BaseAuthorizationTokenProvider(this.cosmosKeyCredential);
        } else if (masterKeyOrResourceToken != null && ResourceTokenAuthorizationHelper.isResourceToken(masterKeyOrResourceToken)) {
            this.authorizationTokenProvider = null;
            hasAuthKeyResourceToken = true;
        } else if(masterKeyOrResourceToken != null && !ResourceTokenAuthorizationHelper.isResourceToken(masterKeyOrResourceToken)){
            this.cosmosKeyCredential = new CosmosKeyCredential(this.masterKeyOrResourceToken);
            hasAuthKeyResourceToken = false;
            this.authorizationTokenProvider = new BaseAuthorizationTokenProvider(this.cosmosKeyCredential);
        } else {
            hasAuthKeyResourceToken = false;
            this.authorizationTokenProvider = null;
        }

        if (connectionPolicy != null) {
            this.connectionPolicy = connectionPolicy;
        } else {
            this.connectionPolicy = new ConnectionPolicy();
        }

        this.sessionContainer = new SessionContainer(this.serviceEndpoint.getHost());
        this.consistencyLevel = consistencyLevel;

        this.userAgentContainer = new UserAgentContainer();

        String userAgentSuffix = this.connectionPolicy.getUserAgentSuffix();
        if (userAgentSuffix != null && userAgentSuffix.length() > 0) {
            userAgentContainer.setSuffix(userAgentSuffix);
        }

        this.reactorHttpClient = httpClient();
        this.globalEndpointManager = new GlobalEndpointManager(asDatabaseAccountManagerInternal(), this.connectionPolicy, /**/configs);
        this.retryPolicy = new RetryPolicy(this.globalEndpointManager, this.connectionPolicy);
        this.resetSessionTokenRetryPolicy = retryPolicy;
    }

    private void initializeGatewayConfigurationReader() {
        String resourceToken;
        if(this.tokenResolver != null) {
            resourceToken = this.tokenResolver.getAuthorizationToken("GET", "", CosmosResourceType.System, null);
        } else if(!this.hasAuthKeyResourceToken && this.authorizationTokenProvider == null) {
            resourceToken = this.firstResourceTokenFromPermissionFeed;
        } else {
            assert  this.masterKeyOrResourceToken != null || this.cosmosKeyCredential != null;
            resourceToken = this.masterKeyOrResourceToken;
        }

        this.gatewayConfigurationReader = new GatewayServiceConfigurationReader(this.serviceEndpoint,
                this.hasAuthKeyResourceToken,
                resourceToken,
                this.connectionPolicy,
                this.authorizationTokenProvider,
                this.reactorHttpClient);

        DatabaseAccount databaseAccount = this.gatewayConfigurationReader.initializeReaderAsync().block();
        this.useMultipleWriteLocations = this.connectionPolicy.getUsingMultipleWriteLocations() && BridgeInternal.isEnableMultipleWriteLocations(databaseAccount);

        // TODO: add support for openAsync
        // https://msdata.visualstudio.com/CosmosDB/_workitems/edit/332589
        this.globalEndpointManager.refreshLocationAsync(databaseAccount, false).block();
    }

    public void init() {

        // TODO: add support for openAsync
        // https://msdata.visualstudio.com/CosmosDB/_workitems/edit/332589
        this.gatewayProxy = createRxGatewayProxy(this.sessionContainer,
                this.consistencyLevel,
                this.queryCompatibilityMode,
                this.userAgentContainer,
                this.globalEndpointManager,
                this.reactorHttpClient);
        this.globalEndpointManager.init();
        this.initializeGatewayConfigurationReader();

        this.collectionCache = new RxClientCollectionCache(this.sessionContainer, this.gatewayProxy, this, this.retryPolicy);
        this.resetSessionTokenRetryPolicy = new ResetSessionTokenRetryPolicyFactory(this.sessionContainer, this.collectionCache, this.retryPolicy);

        this.partitionKeyRangeCache = new RxPartitionKeyRangeCache(RxDocumentClientImpl.this,
                collectionCache);

        if (this.connectionPolicy.getConnectionMode() == ConnectionMode.GATEWAY) {
            this.storeModel = this.gatewayProxy;
        } else {
            this.initializeDirectConnectivity();
        }
    }

    private void initializeDirectConnectivity() {

        this.storeClientFactory = new StoreClientFactory(
            this.configs,
            this.connectionPolicy.getRequestTimeoutInMillis() / 1000,
           // this.maxConcurrentConnectionOpenRequests,
            0,
            this.userAgentContainer
        );

        this.addressResolver = new GlobalAddressResolver(
            this.reactorHttpClient,
            this.globalEndpointManager,
            this.configs.getProtocol(),
            this,
            this.collectionCache,
            this.partitionKeyRangeCache,
            userAgentContainer,
            // TODO: GATEWAY Configuration Reader
            //     this.gatewayConfigurationReader,
            null,
            this.connectionPolicy);

        this.createStoreModel(true);
    }

    DatabaseAccountManagerInternal asDatabaseAccountManagerInternal() {
        return new DatabaseAccountManagerInternal() {

            @Override
            public URI getServiceEndpoint() {
                return RxDocumentClientImpl.this.getServiceEndpoint();
            }

            @Override
            public Flux<DatabaseAccount> getDatabaseAccountFromEndpoint(URI endpoint) {
                logger.info("Getting database account endpoint from {}", endpoint);
                return RxDocumentClientImpl.this.getDatabaseAccountFromEndpoint(endpoint);
            }

            @Override
            public ConnectionPolicy getConnectionPolicy() {
                return RxDocumentClientImpl.this.getConnectionPolicy();
            }
        };
    }

    RxGatewayStoreModel createRxGatewayProxy(ISessionContainer sessionContainer,
                                             ConsistencyLevel consistencyLevel,
                                             QueryCompatibilityMode queryCompatibilityMode,
                                             UserAgentContainer userAgentContainer,
                                             GlobalEndpointManager globalEndpointManager,
                                             HttpClient httpClient) {
        return new RxGatewayStoreModel(sessionContainer,
                consistencyLevel,
                queryCompatibilityMode,
                userAgentContainer,
                globalEndpointManager,
                httpClient);
    }

    private HttpClient httpClient() {

        HttpClientConfig httpClientConfig = new HttpClientConfig(this.configs)
                .withMaxIdleConnectionTimeoutInMillis(this.connectionPolicy.getIdleConnectionTimeoutInMillis())
                .withPoolSize(this.connectionPolicy.getMaxPoolSize())
                .withHttpProxy(this.connectionPolicy.getProxy())
                .withRequestTimeoutInMillis(this.connectionPolicy.getRequestTimeoutInMillis());

        return HttpClient.createFixed(httpClientConfig);
    }

    private void createStoreModel(boolean subscribeRntbdStatus) {
        // EnableReadRequestsFallback, if not explicitly set on the connection policy,
        // is false if the account's consistency is bounded staleness,
        // and true otherwise.

        StoreClient storeClient = this.storeClientFactory.createStoreClient(
                this.addressResolver,
                this.sessionContainer,
                this.gatewayConfigurationReader,
                this,
                false
        );

        this.storeModel = new ServerStoreModel(storeClient);
    }


    @Override
    public URI getServiceEndpoint() {
        return this.serviceEndpoint;
    }

    @Override
    public URI getWriteEndpoint() {
        return globalEndpointManager.getWriteEndpoints().stream().findFirst().orElse(null);
    }

    @Override
    public URI getReadEndpoint() {
        return globalEndpointManager.getReadEndpoints().stream().findFirst().orElse(null);
    }

    @Override
    public ConnectionPolicy getConnectionPolicy() {
        return this.connectionPolicy;
    }

    @Override
    public Mono<ResourceResponse<Database>> createDatabase(Database database, RequestOptions options) {
        if (database == null) {
            throw new IllegalArgumentException("Database");
        }

        logger.debug("Creating a Database. id: [{}]", database.getId());
        validateResource(database);

        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Create,
            ResourceType.Database, Paths.DATABASES_ROOT, database, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();

        return ObservableHelper.inlineIfPossibleAsObs(() -> createDatabaseInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Database>> createDatabaseInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            return this.create(request, retryPolicyInstance).map(response -> toResourceResponse(response, Database.class));
        } catch (Exception e) {
            logger.debug("Failure in creating a database. due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Database>> deleteDatabase(String databaseLink, RequestOptions options) {
        if (StringUtils.isEmpty(databaseLink)) {
            throw new IllegalArgumentException("databaseLink");
        }

        logger.debug("Deleting a Database. databaseLink: [{}]", databaseLink);
        String path = Utils.joinPath(databaseLink, null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
            ResourceType.Database, path, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteDatabaseInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Database>> deleteDatabaseInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, Database.class));
        } catch (Exception e) {
            logger.debug("Failure in deleting a database. due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Database>> readDatabase(String databaseLink, RequestOptions options) {
        if (StringUtils.isEmpty(databaseLink)) {
            throw new IllegalArgumentException("databaseLink");
        }

        logger.debug("Reading a Database. databaseLink: [{}]", databaseLink);
        String path = Utils.joinPath(databaseLink, null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
            ResourceType.Database, path, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readDatabaseInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Database>> readDatabaseInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, Database.class));
        } catch (Exception e) {
            logger.debug("Failure in reading a database. due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<Database>> readDatabases(FeedOptions options) {
        return readFeed(options, ResourceType.Database, Database.class, Paths.DATABASES_ROOT);
    }

    private String parentResourceLinkToQueryLink(String parentResouceLink, ResourceType resourceTypeEnum) {
        switch (resourceTypeEnum) {
            case Database:
                return Paths.DATABASES_ROOT;

            case DocumentCollection:
                return Utils.joinPath(parentResouceLink, Paths.COLLECTIONS_PATH_SEGMENT);

            case Document:
                return Utils.joinPath(parentResouceLink, Paths.DOCUMENTS_PATH_SEGMENT);

            case Offer:
                return Paths.OFFERS_ROOT;

            case User:
                return Utils.joinPath(parentResouceLink, Paths.USERS_PATH_SEGMENT);

            case Permission:
                return Utils.joinPath(parentResouceLink, Paths.PERMISSIONS_PATH_SEGMENT);

            case Attachment:
                return Utils.joinPath(parentResouceLink, Paths.ATTACHMENTS_PATH_SEGMENT);

            case StoredProcedure:
                return Utils.joinPath(parentResouceLink, Paths.STORED_PROCEDURES_PATH_SEGMENT);

            case Trigger:
                return Utils.joinPath(parentResouceLink, Paths.TRIGGERS_PATH_SEGMENT);

            case UserDefinedFunction:
                return Utils.joinPath(parentResouceLink, Paths.USER_DEFINED_FUNCTIONS_PATH_SEGMENT);

            default:
                throw new IllegalArgumentException("resource type not supported");
        }
    }

    private <T extends Resource> Flux<FeedResponse<T>> createQuery(
            String parentResourceLink,
            SqlQuerySpec sqlQuery,
            FeedOptions options,
            Class<T> klass,
            ResourceType resourceTypeEnum) {

        String queryResourceLink = parentResourceLinkToQueryLink(parentResourceLink, resourceTypeEnum);

        UUID activityId = Utils.randomUUID();
        IDocumentQueryClient queryClient = DocumentQueryClientImpl(RxDocumentClientImpl.this);
        Flux<? extends IDocumentQueryExecutionContext<T>> executionContext =
                DocumentQueryExecutionContextFactory.createDocumentQueryExecutionContextAsync(queryClient, resourceTypeEnum, klass, sqlQuery , options, queryResourceLink, false, activityId);
        return executionContext.flatMap(IDocumentQueryExecutionContext::executeAsync);
    }


    @Override
    public Flux<FeedResponse<Database>> queryDatabases(String query, FeedOptions options) {
        return queryDatabases(new SqlQuerySpec(query), options);
    }


    @Override
    public Flux<FeedResponse<Database>> queryDatabases(SqlQuerySpec querySpec, FeedOptions options) {
        return createQuery(Paths.DATABASES_ROOT, querySpec, options, Database.class, ResourceType.Database);
    }

    @Override
    public Mono<ResourceResponse<DocumentCollection>> createCollection(String databaseLink,
                                                                             DocumentCollection collection, RequestOptions options) {
        if (StringUtils.isEmpty(databaseLink)) {
            throw new IllegalArgumentException("databaseLink");
        }
        if (collection == null) {
            throw new IllegalArgumentException("collection");
        }

        logger.debug("Creating a Collection. databaseLink: [{}], Collection id: [{}]", databaseLink,
            collection.getId());
        validateResource(collection);

        String path = Utils.joinPath(databaseLink, Paths.COLLECTIONS_PATH_SEGMENT);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Create,
            ResourceType.DocumentCollection, path, collection, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> this.createCollectionInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<DocumentCollection>> createCollectionInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {


            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.create(request, retryPolicyInstance).map(response -> toResourceResponse(response, DocumentCollection.class))
                    .doOnNext(resourceResponse -> {
                        // set the session token
                        this.sessionContainer.setSessionToken(resourceResponse.getResource().getResourceId(),
                                getAltLink(resourceResponse.getResource()),
                                resourceResponse.getResponseHeaders());
                    });
        } catch (Exception e) {
            logger.debug("Failure in creating a collection. due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<DocumentCollection>> replaceCollection(DocumentCollection collection,
                                                                              RequestOptions options) {
        if (collection == null) {
            throw new IllegalArgumentException("collection");
        }

        logger.debug("Replacing a Collection. id: [{}]", collection.getId());
        validateResource(collection);

        String path = Utils.joinPath(collection.getSelfLink(), null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);

        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
            ResourceType.DocumentCollection, path, collection, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceCollectionInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<DocumentCollection>> replaceCollectionInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {


            // TODO: .Net has some logic for updating session token which we don't
            // have here
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.replace(request, retryPolicyInstance).map(response -> toResourceResponse(response, DocumentCollection.class))
                    .doOnNext(resourceResponse -> {
                        if (resourceResponse.getResource() != null) {
                            // set the session token
                            this.sessionContainer.setSessionToken(resourceResponse.getResource().getResourceId(),
                                    getAltLink(resourceResponse.getResource()),
                                    resourceResponse.getResponseHeaders());
                        }
                    });

        } catch (Exception e) {
            logger.debug("Failure in replacing a collection. due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<DocumentCollection>> deleteCollection(String collectionLink,
                                                                             RequestOptions options) {
        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        logger.debug("Deleting a Collection. collectionLink: [{}]", collectionLink);
        String path = Utils.joinPath(collectionLink, null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
            ResourceType.DocumentCollection, path, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteCollectionInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<DocumentCollection>> deleteCollectionInternal(DocumentClientRetryPolicy retryPolicyInstance,RxDocumentServiceRequest request) {
        try {
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, DocumentCollection.class));

        } catch (Exception e) {
            logger.debug("Failure in deleting a collection, due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    private Mono<RxDocumentServiceResponse> delete(RxDocumentServiceRequest request, DocumentClientRetryPolicy documentClientRetryPolicy) {
        populateHeaders(request, HttpConstants.HttpMethods.DELETE);
        if(request.requestContext != null && documentClientRetryPolicy.getRetryCount() > 0) {
            documentClientRetryPolicy.updateEndTime();
            request.requestContext.updateRetryContext(documentClientRetryPolicy, true);
        }

        return getStoreProxy(request).processMessage(request);
    }

    private Mono<RxDocumentServiceResponse> read(RxDocumentServiceRequest request, DocumentClientRetryPolicy documentClientRetryPolicy) {
        populateHeaders(request, HttpConstants.HttpMethods.GET);
        if(request.requestContext != null && documentClientRetryPolicy.getRetryCount() > 0) {
            documentClientRetryPolicy.updateEndTime();
            request.requestContext.updateRetryContext(documentClientRetryPolicy, true);
        }

        return getStoreProxy(request).processMessage(request);
    }

    Mono<RxDocumentServiceResponse> readFeed(RxDocumentServiceRequest request) {
        populateHeaders(request, HttpConstants.HttpMethods.GET);
        return gatewayProxy.processMessage(request);
    }

    private Mono<RxDocumentServiceResponse> query(RxDocumentServiceRequest request) {
        populateHeaders(request, HttpConstants.HttpMethods.POST);
        return this.getStoreProxy(request).processMessage(request)
                .map(response -> {
                            this.captureSessionToken(request, response);
                            return response;
                        }
                );
    }

    @Override
    public Mono<ResourceResponse<DocumentCollection>> readCollection(String collectionLink,
                                                                           RequestOptions options) {
        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        logger.debug("Reading a Collection. collectionLink: [{}]", collectionLink);
        String path = Utils.joinPath(collectionLink, null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
            ResourceType.DocumentCollection, path, requestHeaders, options);

        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readCollectionInternal(retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<DocumentCollection>> readCollectionInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {

        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, DocumentCollection.class));
        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in reading a collection, due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<DocumentCollection>> readCollections(String databaseLink, FeedOptions options) {

        if (StringUtils.isEmpty(databaseLink)) {
            throw new IllegalArgumentException("databaseLink");
        }

        return readFeed(options, ResourceType.DocumentCollection, DocumentCollection.class,
                Utils.joinPath(databaseLink, Paths.COLLECTIONS_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<DocumentCollection>> queryCollections(String databaseLink, String query,
                                                                         FeedOptions options) {
        return createQuery(databaseLink, new SqlQuerySpec(query), options, DocumentCollection.class, ResourceType.DocumentCollection);
    }

    @Override
    public Flux<FeedResponse<DocumentCollection>> queryCollections(String databaseLink,
                                                                         SqlQuerySpec querySpec, FeedOptions options) {
        return createQuery(databaseLink, querySpec, options, DocumentCollection.class, ResourceType.DocumentCollection);
    }

    private static String serializeProcedureParams(Object[] objectArray) {
        String[] stringArray = new String[objectArray.length];

        for (int i = 0; i < objectArray.length; ++i) {
            Object object = objectArray[i];
            if (object instanceof JsonSerializable) {
                stringArray[i] = ((JsonSerializable) object).toJson();
            } else {

                // POJO, ObjectNode, number, STRING or Boolean
                try {
                    stringArray[i] = mapper.writeValueAsString(object);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Can't serialize the object into the json string", e);
                }
            }
        }

        return String.format("[%s]", StringUtils.join(stringArray, ","));
    }

    private static void validateResource(Resource resource) {
        if (!StringUtils.isEmpty(resource.getId())) {
            if (resource.getId().indexOf('/') != -1 || resource.getId().indexOf('\\') != -1 ||
                    resource.getId().indexOf('?') != -1 || resource.getId().indexOf('#') != -1) {
                throw new IllegalArgumentException("Id contains illegal chars.");
            }

            if (resource.getId().endsWith(" ")) {
                throw new IllegalArgumentException("Id ends with a space.");
            }
        }
    }

    private Map<String, String> getRequestHeaders(RequestOptions options) {
        Map<String, String> headers = new HashMap<>();

        if (this.useMultipleWriteLocations) {
            headers.put(HttpConstants.HttpHeaders.ALLOW_TENTATIVE_WRITES, Boolean.TRUE.toString());
        }

        if (consistencyLevel != null) {
            headers.put(HttpConstants.HttpHeaders.CONSISTENCY_LEVEL, consistencyLevel.toString());
        }

        if (options == null) {
            return headers;
        }

        Map<String, String> customOptions = options.getHeaders();
        if (customOptions != null) {
            headers.putAll(customOptions);
        }

        if (options.getAccessCondition() != null) {
            if (options.getAccessCondition().getType() == AccessConditionType.IF_MATCH) {
                headers.put(HttpConstants.HttpHeaders.IF_MATCH, options.getAccessCondition().getCondition());
            } else {
                headers.put(HttpConstants.HttpHeaders.IF_NONE_MATCH, options.getAccessCondition().getCondition());
            }
        }

        if (options.getConsistencyLevel() != null) {
            headers.put(HttpConstants.HttpHeaders.CONSISTENCY_LEVEL, options.getConsistencyLevel().toString());
        }

        if (options.getIndexingDirective() != null) {
            headers.put(HttpConstants.HttpHeaders.INDEXING_DIRECTIVE, options.getIndexingDirective().toString());
        }

        if (options.getPostTriggerInclude() != null && options.getPostTriggerInclude().size() > 0) {
            String postTriggerInclude = StringUtils.join(options.getPostTriggerInclude(), ",");
            headers.put(HttpConstants.HttpHeaders.POST_TRIGGER_INCLUDE, postTriggerInclude);
        }

        if (options.getPreTriggerInclude() != null && options.getPreTriggerInclude().size() > 0) {
            String preTriggerInclude = StringUtils.join(options.getPreTriggerInclude(), ",");
            headers.put(HttpConstants.HttpHeaders.PRE_TRIGGER_INCLUDE, preTriggerInclude);
        }

        if (!Strings.isNullOrEmpty(options.getSessionToken())) {
            headers.put(HttpConstants.HttpHeaders.SESSION_TOKEN, options.getSessionToken());
        }

        if (options.getResourceTokenExpirySeconds() != null) {
            headers.put(HttpConstants.HttpHeaders.RESOURCE_TOKEN_EXPIRY,
                    String.valueOf(options.getResourceTokenExpirySeconds()));
        }

        if (options.getOfferThroughput() != null && options.getOfferThroughput() >= 0) {
            headers.put(HttpConstants.HttpHeaders.OFFER_THROUGHPUT, options.getOfferThroughput().toString());
        } else if (options.getOfferType() != null) {
            headers.put(HttpConstants.HttpHeaders.OFFER_TYPE, options.getOfferType());
        }

        if (options.isPopulateQuotaInfo()) {
            headers.put(HttpConstants.HttpHeaders.POPULATE_QUOTA_INFO, String.valueOf(true));
        }

        if (options.isScriptLoggingEnabled()) {
            headers.put(HttpConstants.HttpHeaders.SCRIPT_ENABLE_LOGGING, String.valueOf(true));
        }

        return headers;
    }

    private Mono<RxDocumentServiceRequest> addPartitionKeyInformation(RxDocumentServiceRequest request,
                                                                      ByteBuffer contentAsByteBuffer,
                                                                      Document document,
                                                                      RequestOptions options) {

        Mono<Utils.ValueHolder<DocumentCollection>> collectionObs = this.collectionCache.resolveCollectionAsync(request);
        return collectionObs
                .map(collectionValueHolder -> {
                    addPartitionKeyInformation(request, contentAsByteBuffer, document, options, collectionValueHolder.v);
                    return request;
                });
    }

    private Mono<RxDocumentServiceRequest> addPartitionKeyInformation(RxDocumentServiceRequest request,
                                                                      ByteBuffer contentAsByteBuffer,
                                                                      Object document,
                                                                      RequestOptions options,
                                                                      Mono<Utils.ValueHolder<DocumentCollection>> collectionObs) {

        return collectionObs.map(collectionValueHolder -> {
            addPartitionKeyInformation(request, contentAsByteBuffer, document, options, collectionValueHolder.v);
            return request;
        });
    }

    private void addPartitionKeyInformation(RxDocumentServiceRequest request,
                                            ByteBuffer contentAsByteBuffer,
                                            Object objectDoc, RequestOptions options,
                                            DocumentCollection collection) {
        PartitionKeyDefinition partitionKeyDefinition = collection.getPartitionKey();

        PartitionKeyInternal partitionKeyInternal = null;
        if (options != null && options.getPartitionKey() != null && options.getPartitionKey().equals(PartitionKey.NONE)){
            partitionKeyInternal = BridgeInternal.getNonePartitionKey(partitionKeyDefinition);
        } else if (options != null && options.getPartitionKey() != null) {
            partitionKeyInternal = BridgeInternal.getPartitionKeyInternal(options.getPartitionKey());
        } else if (partitionKeyDefinition == null || partitionKeyDefinition.getPaths().size() == 0) {
            // For backward compatibility, if collection doesn't have partition key defined, we assume all documents
            // have empty value for it and user doesn't need to specify it explicitly.
            partitionKeyInternal = PartitionKeyInternal.getEmpty();
        } else if (contentAsByteBuffer != null) {
            CosmosItemProperties cosmosItemProperties;
            if (objectDoc instanceof CosmosItemProperties) {
                cosmosItemProperties = (CosmosItemProperties) objectDoc;
            } else {
                cosmosItemProperties = new CosmosItemProperties(contentAsByteBuffer);
            }

            partitionKeyInternal = extractPartitionKeyValueFromDocument(cosmosItemProperties, partitionKeyDefinition);
        } else {
            throw new UnsupportedOperationException("PartitionKey value must be supplied for this operation.");
        }

        request.getHeaders().put(HttpConstants.HttpHeaders.PARTITION_KEY, escapeNonAscii(partitionKeyInternal.toJson()));
    }

    private static String escapeNonAscii(String partitionKeyJson) {
        StringBuilder sb = new StringBuilder(partitionKeyJson.length());
        for (int i = 0; i < partitionKeyJson.length(); i++) {
            int val = partitionKeyJson.charAt(i);
            if (val > 127) {
                sb.append("\\u").append(String.format("%04X", val));
            } else {
                sb.append(partitionKeyJson.charAt(i));
            }
        }
        return sb.toString();
    }

    private static PartitionKeyInternal extractPartitionKeyValueFromDocument(
            CosmosItemProperties document,
            PartitionKeyDefinition partitionKeyDefinition) {
        if (partitionKeyDefinition != null) {
            String path = partitionKeyDefinition.getPaths().iterator().next();
            List<String> parts = PathParser.getPathParts(path);
            if (parts.size() >= 1) {
                Object value = document.getObjectByPath(parts);
                if (value == null || value.getClass() == ObjectNode.class) {
                    value = BridgeInternal.getNonePartitionKey(partitionKeyDefinition);
                }

                if (value instanceof PartitionKeyInternal) {
                    return (PartitionKeyInternal) value;
                } else {
                    return PartitionKeyInternal.fromObjectArray(Collections.singletonList(value), false);
                }
            }
        }

        return null;
    }

    private Mono<RxDocumentServiceRequest> getCreateDocumentRequest(String documentCollectionLink, Object document,
                                                                      RequestOptions options, boolean disableAutomaticIdGeneration, OperationType operationType) {

        if (StringUtils.isEmpty(documentCollectionLink)) {
            throw new IllegalArgumentException("documentCollectionLink");
        }
        if (document == null) {
            throw new IllegalArgumentException("document");
        }

        ByteBuffer content = serializeJsonToByteBuffer(document, mapper);

        String path = Utils.joinPath(documentCollectionLink, Paths.DOCUMENTS_PATH_SEGMENT);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);

        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(operationType, ResourceType.Document, path,
                                                                           requestHeaders, options, content);

        Mono<Utils.ValueHolder<DocumentCollection>> collectionObs = this.collectionCache.resolveCollectionAsync(request);
        return addPartitionKeyInformation(request, content, document, options, collectionObs);
    }

    private void populateHeaders(RxDocumentServiceRequest request, String httpMethod) {
        request.getHeaders().put(HttpConstants.HttpHeaders.X_DATE, Utils.nowAsRFC1123());
        if (this.masterKeyOrResourceToken != null || this.resourceTokensMap != null
            || this.tokenResolver != null || this.cosmosKeyCredential != null) {
            String resourceName = request.getResourceAddress();

            String authorization = this.getUserAuthorizationToken(
                    resourceName, request.getResourceType(), httpMethod, request.getHeaders(),
                    AuthorizationTokenType.PrimaryMasterKey, request.properties);
            try {
                authorization = URLEncoder.encode(authorization, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException("Failed to encode authtoken.", e);
            }
            request.getHeaders().put(HttpConstants.HttpHeaders.AUTHORIZATION, authorization);
        }

        if ((HttpConstants.HttpMethods.POST.equals(httpMethod) || HttpConstants.HttpMethods.PUT.equals(httpMethod))
                && !request.getHeaders().containsKey(HttpConstants.HttpHeaders.CONTENT_TYPE)) {
            request.getHeaders().put(HttpConstants.HttpHeaders.CONTENT_TYPE, RuntimeConstants.MediaTypes.JSON);
        }

        if (!request.getHeaders().containsKey(HttpConstants.HttpHeaders.ACCEPT)) {
            request.getHeaders().put(HttpConstants.HttpHeaders.ACCEPT, RuntimeConstants.MediaTypes.JSON);
        }
    }

    @Override
    public String getUserAuthorizationToken(String resourceName,
                                            ResourceType resourceType,
                                            String requestVerb,
                                            Map<String, String> headers,
                                            AuthorizationTokenType tokenType,
                                            Map<String, Object> properties) {

        if (this.tokenResolver != null) {
            return this.tokenResolver.getAuthorizationToken(requestVerb, resourceName, this.resolveCosmosResourceType(resourceType),
                    properties != null ? Collections.unmodifiableMap(properties) : null);
        } else if (cosmosKeyCredential != null) {
            return this.authorizationTokenProvider.generateKeyAuthorizationSignature(requestVerb, resourceName,
                    resourceType, headers);
        } else if (masterKeyOrResourceToken != null && hasAuthKeyResourceToken && resourceTokensMap == null) {
            return masterKeyOrResourceToken;
        } else {
            assert resourceTokensMap != null;
            if(resourceType.equals(ResourceType.DatabaseAccount)) {
                return this.firstResourceTokenFromPermissionFeed;
            }
            return ResourceTokenAuthorizationHelper.getAuthorizationTokenUsingResourceTokens(resourceTokensMap, requestVerb, resourceName, headers);
        }
    }

    private CosmosResourceType resolveCosmosResourceType(ResourceType resourceType) {
        try {
            return CosmosResourceType.valueOf(resourceType.toString());
        } catch (IllegalArgumentException e) {
            return CosmosResourceType.System;
        }
    }

    void captureSessionToken(RxDocumentServiceRequest request, RxDocumentServiceResponse response) {
        this.sessionContainer.setSessionToken(request, response.getResponseHeaders());
    }

    private Mono<RxDocumentServiceResponse> create(RxDocumentServiceRequest request, DocumentClientRetryPolicy retryPolicy) {
        populateHeaders(request, HttpConstants.HttpMethods.POST);
        RxStoreModel storeProxy = this.getStoreProxy(request);
        if(request.requestContext != null && retryPolicy.getRetryCount() > 0) {
            retryPolicy.updateEndTime();
            request.requestContext.updateRetryContext(retryPolicy, true);
        }

        return storeProxy.processMessage(request);
    }

    private Mono<RxDocumentServiceResponse> upsert(RxDocumentServiceRequest request, DocumentClientRetryPolicy documentClientRetryPolicy) {

        populateHeaders(request, HttpConstants.HttpMethods.POST);
        Map<String, String> headers = request.getHeaders();
        // headers can never be null, since it will be initialized even when no
        // request options are specified,
        // hence using assertion here instead of exception, being in the private
        // method
        assert (headers != null);
        headers.put(HttpConstants.HttpHeaders.IS_UPSERT, "true");
        if(request.requestContext != null && documentClientRetryPolicy.getRetryCount() > 0) {
            documentClientRetryPolicy.updateEndTime();
            request.requestContext.updateRetryContext(documentClientRetryPolicy, true);
        }

        return getStoreProxy(request).processMessage(request)
                .map(response -> {
                            this.captureSessionToken(request, response);
                            return response;
                        }
                );
    }

    private Mono<RxDocumentServiceResponse> replace(RxDocumentServiceRequest request, DocumentClientRetryPolicy documentClientRetryPolicy) {
        populateHeaders(request, HttpConstants.HttpMethods.PUT);
        if(request.requestContext != null && documentClientRetryPolicy.getRetryCount() > 0) {
            documentClientRetryPolicy.updateEndTime();
            request.requestContext.updateRetryContext(documentClientRetryPolicy, true);
        }

        return getStoreProxy(request).processMessage(request);
    }

    @Override
    public Mono<ResourceResponse<Document>> createDocument(String collectionLink, Object document,
                                                                 RequestOptions options, boolean disableAutomaticIdGeneration) {
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        if (options == null || options.getPartitionKey() == null) {
            requestRetryPolicy = new PartitionKeyMismatchRetryPolicy(collectionCache, requestRetryPolicy, collectionLink, options);
        }

        DocumentClientRetryPolicy documentClientRetryPolicy = requestRetryPolicy;
        logger.debug("Creating a Document. collectionLink: [{}]", collectionLink);

        Mono<RxDocumentServiceRequest> requestObs = getCreateDocumentRequest(collectionLink, document,
            options, disableAutomaticIdGeneration, OperationType.Create);
        return requestObs
            .flatMap(req -> {
                return ObservableHelper.inlineIfPossibleAsObs(() -> createDocumentInternal(documentClientRetryPolicy, req), documentClientRetryPolicy);
            });
    }

    private Mono<ResourceResponse<Document>> createDocumentInternal(DocumentClientRetryPolicy requestRetryPolicy, RxDocumentServiceRequest request) {
        try {
            if (requestRetryPolicy != null) {
                requestRetryPolicy.onBeforeSendRequest(request);
            }
            Mono<RxDocumentServiceResponse> responseObservable = create(request, requestRetryPolicy);

            return responseObservable
                    .map(serviceResponse -> toResourceResponse(serviceResponse, Document.class));

        } catch (Exception e) {
            logger.debug("Failure in creating a document due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Document>> upsertDocument(String collectionLink, Object document,
                                                                 RequestOptions options, boolean disableAutomaticIdGeneration) {

        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        if (options == null || options.getPartitionKey() == null) {
            requestRetryPolicy = new PartitionKeyMismatchRetryPolicy(collectionCache, requestRetryPolicy, collectionLink, options);
        }

        logger.debug("Upserting a Document. collectionLink: [{}]", collectionLink);
        DocumentClientRetryPolicy finalRetryPolicyInstance = requestRetryPolicy;
        Mono<RxDocumentServiceRequest> reqObs = getCreateDocumentRequest(collectionLink, document,
            options, disableAutomaticIdGeneration, OperationType.Upsert);
        return reqObs.flatMap(req -> {
            return ObservableHelper.inlineIfPossibleAsObs(() -> upsertDocumentInternal(finalRetryPolicyInstance, req), finalRetryPolicyInstance);
            });
    }

    private Mono<ResourceResponse<Document>> upsertDocumentInternal(DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            Mono<RxDocumentServiceResponse> responseObservable = upsert(request, retryPolicyInstance);
            return responseObservable
                    .map(serviceResponse -> toResourceResponse(serviceResponse, Document.class));
        } catch (Exception e) {
            logger.debug("Failure in upserting a document due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Document>> replaceDocument(String documentLink, Object document,
                                                            RequestOptions options) {

        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        if (options == null || options.getPartitionKey() == null) {
            String collectionLink = Utils.getCollectionName(documentLink);
            requestRetryPolicy = new PartitionKeyMismatchRetryPolicy(collectionCache, requestRetryPolicy, collectionLink, options);
        }
        DocumentClientRetryPolicy finalRequestRetryPolicy = requestRetryPolicy;
        if (document == null) {
            throw new IllegalArgumentException("document");
        }
        if (StringUtils.isEmpty(documentLink)) {
            throw new IllegalArgumentException("documentLink");
        }

        if (document == null) {
            throw new IllegalArgumentException("document");
        }

        Document typedDocument = documentFromObject(document, mapper);
        logger.debug("Replacing a Document. documentLink: [{}]", documentLink);
        final String path = Utils.joinPath(documentLink, null);
        final Map<String, String> requestHeaders = getRequestHeaders(options);

        ByteBuffer content = serializeJsonToByteBuffer(typedDocument, mapper);

        final RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
            ResourceType.Document, path, requestHeaders, options, content);
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceDocumentInternal(typedDocument, options, content, finalRequestRetryPolicy, request), finalRequestRetryPolicy);
    }

    @Override
    public Mono<ResourceResponse<Document>> replaceDocument(Document document, RequestOptions options) {
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        if (options == null || options.getPartitionKey() == null) {
            String collectionLink = document.getSelfLink();
            requestRetryPolicy = new PartitionKeyMismatchRetryPolicy(collectionCache, requestRetryPolicy, collectionLink, options);
        }
        DocumentClientRetryPolicy finalRequestRetryPolicy = requestRetryPolicy;
        if (document == null) {
            throw new IllegalArgumentException("document");
        }

        logger.debug("Replacing a Document. documentLink: [{}]", document.getSelfLink());
        final String path = Utils.joinPath(document.getSelfLink(), null);
        final Map<String, String> requestHeaders = getRequestHeaders(options);

        ByteBuffer content = serializeJsonToByteBuffer(document, mapper);

        final RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
            ResourceType.Document, path, requestHeaders, options, content);

        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceDocumentInternal(document, options, content, finalRequestRetryPolicy, request), requestRetryPolicy);
    }

    private Mono<ResourceResponse<Document>> replaceDocumentInternal(Document document, RequestOptions options, ByteBuffer contentAsByteBuffer, DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {

        try {
            if (document == null) {
                throw new IllegalArgumentException("document");
            }

            return this.replaceDocumentInternal(document.getSelfLink(), document, options, contentAsByteBuffer, retryPolicyInstance, request);

        } catch (Exception e) {
            logger.debug("Failure in replacing a database due to [{}]", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<ResourceResponse<Document>> replaceDocumentInternal(String documentLink, Document document,
                                                                     RequestOptions options, ByteBuffer content, DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {

        Mono<Utils.ValueHolder<DocumentCollection>> collectionObs = collectionCache.resolveCollectionAsync(request);
        Mono<RxDocumentServiceRequest> requestObs = addPartitionKeyInformation(request, content, document, options, collectionObs);

        return requestObs.flatMap(req -> {
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            return replace(request, retryPolicyInstance)
                .map(resp -> toResourceResponse(resp, Document.class));} );
    }

    @Override
    public Mono<ResourceResponse<Document>> deleteDocument(String documentLink, RequestOptions options) {
        if (StringUtils.isEmpty(documentLink)) {
            throw new IllegalArgumentException("documentLink");
        }

        logger.debug("Deleting a Document. documentLink: [{}]", documentLink);
        String path = Utils.joinPath(documentLink, null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
            ResourceType.Document, path, requestHeaders, options);
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteDocumentInternal(options, requestRetryPolicy, request), requestRetryPolicy);
    }

    private Mono<ResourceResponse<Document>> deleteDocumentInternal(RequestOptions options,
                                                                          DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {
            Mono<Utils.ValueHolder<DocumentCollection>> collectionObs = collectionCache.resolveCollectionAsync(request);

            Mono<RxDocumentServiceRequest> requestObs = addPartitionKeyInformation(request, null, null, options, collectionObs);

            return requestObs.flatMap(req -> {
                if (retryPolicyInstance != null) {
                    retryPolicyInstance.onBeforeSendRequest(req);
                }
                return this.delete(req, retryPolicyInstance)
                        .map(serviceResponse -> toResourceResponse(serviceResponse, Document.class));});

        } catch (Exception e) {
            logger.debug("Failure in deleting a document due to [{}]", e.getMessage());
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Document>> readDocument(String documentLink, RequestOptions options) {
        if (StringUtils.isEmpty(documentLink)) {
            throw new IllegalArgumentException("documentLink");
        }

        logger.debug("Reading a Document. documentLink: [{}]", documentLink);
        String path = Utils.joinPath(documentLink, null);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
            ResourceType.Document, path, requestHeaders, options);
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readDocumentInternal(options, retryPolicyInstance, request), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Document>> readDocumentInternal(RequestOptions options,
                                                                        DocumentClientRetryPolicy retryPolicyInstance, RxDocumentServiceRequest request) {
        try {
            Mono<Utils.ValueHolder<DocumentCollection>> collectionObs = this.collectionCache.resolveCollectionAsync(request);

            Mono<RxDocumentServiceRequest> requestObs = addPartitionKeyInformation(request, null, null, options, collectionObs);

            return requestObs.flatMap(req -> {
                if (retryPolicyInstance != null) {
                    retryPolicyInstance.onBeforeSendRequest(request);
                }
                return this.read(request, retryPolicyInstance).map(serviceResponse -> toResourceResponse(serviceResponse, Document.class));
            });

        } catch (Exception e) {
            logger.debug("Failure in reading a document due to [{}]", e.getMessage());
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<Document>> readDocuments(String collectionLink, FeedOptions options) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        return queryDocuments(collectionLink, "SELECT * FROM r", options);
    }

    @Override
    public Flux<FeedResponse<Document>> queryDocuments(String collectionLink, String query,
                                                             FeedOptions options) {
        return queryDocuments(collectionLink, new SqlQuerySpec(query), options);
    }

    private IDocumentQueryClient DocumentQueryClientImpl(RxDocumentClientImpl rxDocumentClientImpl) {

        return new IDocumentQueryClient () {

            @Override
            public RxCollectionCache getCollectionCache() {
                return RxDocumentClientImpl.this.collectionCache;
            }

            @Override
            public RxPartitionKeyRangeCache getPartitionKeyRangeCache() {
                return RxDocumentClientImpl.this.partitionKeyRangeCache;
            }

            @Override
            public IRetryPolicyFactory getResetSessionTokenRetryPolicy() {
                return RxDocumentClientImpl.this.resetSessionTokenRetryPolicy;
            }

            @Override
            public ConsistencyLevel getDefaultConsistencyLevelAsync() {
                return RxDocumentClientImpl.this.gatewayConfigurationReader.getDefaultConsistencyLevel();
            }

            @Override
            public ConsistencyLevel getDesiredConsistencyLevelAsync() {
                // TODO Auto-generated method stub
                return RxDocumentClientImpl.this.consistencyLevel;
            }

            @Override
            public Mono<RxDocumentServiceResponse> executeQueryAsync(RxDocumentServiceRequest request) {
                return RxDocumentClientImpl.this.query(request).single();
            }

            @Override
            public QueryCompatibilityMode getQueryCompatibilityMode() {
                // TODO Auto-generated method stub
                return QueryCompatibilityMode.Default;
            }

            @Override
            public Mono<RxDocumentServiceResponse> readFeedAsync(RxDocumentServiceRequest request) {
                // TODO Auto-generated method stub
                return null;
            }
        };
    }

    @Override
    public Flux<FeedResponse<Document>> queryDocuments(String collectionLink, SqlQuerySpec querySpec,
                                                             FeedOptions options) {
        return createQuery(collectionLink, querySpec, options, Document.class, ResourceType.Document);
    }

    @Override
    public Flux<FeedResponse<Document>> queryDocumentChangeFeed(final String collectionLink,
                                                                      final ChangeFeedOptions changeFeedOptions) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        ChangeFeedQueryImpl<Document> changeFeedQueryImpl = new ChangeFeedQueryImpl<Document>(this, ResourceType.Document,
                Document.class, collectionLink, changeFeedOptions);

        return changeFeedQueryImpl.executeAsync();
    }

    @Override
    public Flux<FeedResponse<PartitionKeyRange>> readPartitionKeyRanges(final String collectionLink,
                                                                              FeedOptions options) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        return readFeed(options, ResourceType.PartitionKeyRange, PartitionKeyRange.class,
                Utils.joinPath(collectionLink, Paths.PARTITION_KEY_RANGES_PATH_SEGMENT));
    }

    private RxDocumentServiceRequest getStoredProcedureRequest(String collectionLink, StoredProcedure storedProcedure,
                                                               RequestOptions options, OperationType operationType) {
        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }
        if (storedProcedure == null) {
            throw new IllegalArgumentException("storedProcedure");
        }

        validateResource(storedProcedure);

        String path = Utils.joinPath(collectionLink, Paths.STORED_PROCEDURES_PATH_SEGMENT);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(operationType, ResourceType.StoredProcedure,
                path, storedProcedure, requestHeaders, options);

        return request;
    }

    private RxDocumentServiceRequest getUserDefinedFunctionRequest(String collectionLink, UserDefinedFunction udf,
                                                                   RequestOptions options, OperationType operationType) {
        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }
        if (udf == null) {
            throw new IllegalArgumentException("udf");
        }

        validateResource(udf);

        String path = Utils.joinPath(collectionLink, Paths.USER_DEFINED_FUNCTIONS_PATH_SEGMENT);
        Map<String, String> requestHeaders = this.getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(operationType,
                ResourceType.UserDefinedFunction, path, udf, requestHeaders, options);

        return request;
    }

    @Override
    public Mono<ResourceResponse<StoredProcedure>> createStoredProcedure(String collectionLink,
                                                                               StoredProcedure storedProcedure, RequestOptions options) {
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> createStoredProcedureInternal(collectionLink, storedProcedure, options, requestRetryPolicy), requestRetryPolicy);
    }

    private Mono<ResourceResponse<StoredProcedure>> createStoredProcedureInternal(String collectionLink,
                                                                                        StoredProcedure storedProcedure, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {

            logger.debug("Creating a StoredProcedure. collectionLink: [{}], storedProcedure id [{}]",
                    collectionLink, storedProcedure.getId());
            RxDocumentServiceRequest request = getStoredProcedureRequest(collectionLink, storedProcedure, options,
                    OperationType.Create);
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.create(request, retryPolicyInstance).map(response -> toResourceResponse(response, StoredProcedure.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in creating a StoredProcedure due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<StoredProcedure>> upsertStoredProcedure(String collectionLink,
                                                                               StoredProcedure storedProcedure, RequestOptions options) {
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> upsertStoredProcedureInternal(collectionLink, storedProcedure, options, requestRetryPolicy), requestRetryPolicy);
    }

    private Mono<ResourceResponse<StoredProcedure>> upsertStoredProcedureInternal(String collectionLink,
                                                                                        StoredProcedure storedProcedure, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {

            logger.debug("Upserting a StoredProcedure. collectionLink: [{}], storedProcedure id [{}]",
                    collectionLink, storedProcedure.getId());
            RxDocumentServiceRequest request = getStoredProcedureRequest(collectionLink, storedProcedure, options,
                    OperationType.Upsert);
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.upsert(request, retryPolicyInstance).map(response -> toResourceResponse(response, StoredProcedure.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in upserting a StoredProcedure due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<StoredProcedure>> replaceStoredProcedure(StoredProcedure storedProcedure,
                                                                                RequestOptions options) {
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceStoredProcedureInternal(storedProcedure, options, requestRetryPolicy), requestRetryPolicy);
    }

    private Mono<ResourceResponse<StoredProcedure>> replaceStoredProcedureInternal(StoredProcedure storedProcedure,
                                                                                         RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {

            if (storedProcedure == null) {
                throw new IllegalArgumentException("storedProcedure");
            }
            logger.debug("Replacing a StoredProcedure. storedProcedure id [{}]", storedProcedure.getId());

            RxDocumentClientImpl.validateResource(storedProcedure);

            String path = Utils.joinPath(storedProcedure.getSelfLink(), null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
                    ResourceType.StoredProcedure, path, storedProcedure, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.replace(request, retryPolicyInstance).map(response -> toResourceResponse(response, StoredProcedure.class));

        } catch (Exception e) {
            logger.debug("Failure in replacing a StoredProcedure due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<StoredProcedure>> deleteStoredProcedure(String storedProcedureLink,
                                                                               RequestOptions options) {
        DocumentClientRetryPolicy requestRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteStoredProcedureInternal(storedProcedureLink, options, requestRetryPolicy), requestRetryPolicy);
    }

    private Mono<ResourceResponse<StoredProcedure>> deleteStoredProcedureInternal(String storedProcedureLink,
                                                                                        RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {

            if (StringUtils.isEmpty(storedProcedureLink)) {
                throw new IllegalArgumentException("storedProcedureLink");
            }

            logger.debug("Deleting a StoredProcedure. storedProcedureLink [{}]", storedProcedureLink);
            String path = Utils.joinPath(storedProcedureLink, null);
            Map<String, String> requestHeaders = this.getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
                    ResourceType.StoredProcedure, path, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, StoredProcedure.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in deleting a StoredProcedure due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<StoredProcedure>> readStoredProcedure(String storedProcedureLink,
                                                                             RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readStoredProcedureInternal(storedProcedureLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<StoredProcedure>> readStoredProcedureInternal(String storedProcedureLink,
                                                                                      RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {

        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {

            if (StringUtils.isEmpty(storedProcedureLink)) {
                throw new IllegalArgumentException("storedProcedureLink");
            }

            logger.debug("Reading a StoredProcedure. storedProcedureLink [{}]", storedProcedureLink);
            String path = Utils.joinPath(storedProcedureLink, null);
            Map<String, String> requestHeaders = this.getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.StoredProcedure, path, requestHeaders, options);

            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, StoredProcedure.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in reading a StoredProcedure due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<StoredProcedure>> readStoredProcedures(String collectionLink,
                                                                          FeedOptions options) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        return readFeed(options, ResourceType.StoredProcedure, StoredProcedure.class,
                Utils.joinPath(collectionLink, Paths.STORED_PROCEDURES_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<StoredProcedure>> queryStoredProcedures(String collectionLink, String query,
                                                                           FeedOptions options) {
        return queryStoredProcedures(collectionLink, new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<StoredProcedure>> queryStoredProcedures(String collectionLink,
                                                                           SqlQuerySpec querySpec, FeedOptions options) {
        return createQuery(collectionLink, querySpec, options, StoredProcedure.class, ResourceType.StoredProcedure);
    }

    @Override
    public Mono<StoredProcedureResponse> executeStoredProcedure(String storedProcedureLink,
                                                                      Object[] procedureParams) {
        return this.executeStoredProcedure(storedProcedureLink, null, procedureParams);
    }

    @Override
    public Mono<StoredProcedureResponse> executeStoredProcedure(String storedProcedureLink,
                                                                      RequestOptions options, Object[] procedureParams) {
        DocumentClientRetryPolicy documentClientRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> executeStoredProcedureInternal(storedProcedureLink, options, procedureParams, documentClientRetryPolicy), documentClientRetryPolicy);
    }

    private Mono<StoredProcedureResponse> executeStoredProcedureInternal(String storedProcedureLink,
                                                                               RequestOptions options, Object[] procedureParams, DocumentClientRetryPolicy retryPolicy) {

        try {
            logger.debug("Executing a StoredProcedure. storedProcedureLink [{}]", storedProcedureLink);
            String path = Utils.joinPath(storedProcedureLink, null);

            Map<String, String> requestHeaders = getRequestHeaders(options);
            requestHeaders.put(HttpConstants.HttpHeaders.ACCEPT, RuntimeConstants.MediaTypes.JSON);

            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.ExecuteJavaScript,
                    ResourceType.StoredProcedure, path,
                    procedureParams != null ? RxDocumentClientImpl.serializeProcedureParams(procedureParams) : "",
                    requestHeaders, options);

            Mono<RxDocumentServiceRequest> reqObs = addPartitionKeyInformation(request, null, null, options);
            return reqObs.flatMap(req -> create(request, retryPolicy)
                    .map(response -> {
                        this.captureSessionToken(request, response);
                        return toStoredProcedureResponse(response);
                    }));

        } catch (Exception e) {
            logger.debug("Failure in executing a StoredProcedure due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Trigger>> createTrigger(String collectionLink, Trigger trigger,
                                                               RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> createTriggerInternal(collectionLink, trigger, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Trigger>> createTriggerInternal(String collectionLink, Trigger trigger,
                                                                        RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {

            logger.debug("Creating a Trigger. collectionLink [{}], trigger id [{}]", collectionLink,
                    trigger.getId());
            RxDocumentServiceRequest request = getTriggerRequest(collectionLink, trigger, options,
                    OperationType.Create);
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.create(request, retryPolicyInstance).map(response -> toResourceResponse(response, Trigger.class));

        } catch (Exception e) {
            logger.debug("Failure in creating a Trigger due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Trigger>> upsertTrigger(String collectionLink, Trigger trigger,
                                                               RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> upsertTriggerInternal(collectionLink, trigger, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Trigger>> upsertTriggerInternal(String collectionLink, Trigger trigger,
                                                                        RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {

            logger.debug("Upserting a Trigger. collectionLink [{}], trigger id [{}]", collectionLink,
                    trigger.getId());
            RxDocumentServiceRequest request = getTriggerRequest(collectionLink, trigger, options,
                    OperationType.Upsert);
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.upsert(request, retryPolicyInstance).map(response -> toResourceResponse(response, Trigger.class));

        } catch (Exception e) {
            logger.debug("Failure in upserting a Trigger due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    private RxDocumentServiceRequest getTriggerRequest(String collectionLink, Trigger trigger, RequestOptions options,
                                                       OperationType operationType) {
        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }
        if (trigger == null) {
            throw new IllegalArgumentException("trigger");
        }

        RxDocumentClientImpl.validateResource(trigger);

        String path = Utils.joinPath(collectionLink, Paths.TRIGGERS_PATH_SEGMENT);
        Map<String, String> requestHeaders = getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(operationType, ResourceType.Trigger, path,
                trigger, requestHeaders, options);

        return request;
    }

    @Override
    public Mono<ResourceResponse<Trigger>> replaceTrigger(Trigger trigger, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceTriggerInternal(trigger, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Trigger>> replaceTriggerInternal(Trigger trigger, RequestOptions options,
                                                                         DocumentClientRetryPolicy retryPolicyInstance) {

        try {
            if (trigger == null) {
                throw new IllegalArgumentException("trigger");
            }

            logger.debug("Replacing a Trigger. trigger id [{}]", trigger.getId());
            RxDocumentClientImpl.validateResource(trigger);

            String path = Utils.joinPath(trigger.getSelfLink(), null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
                    ResourceType.Trigger, path, trigger, requestHeaders, options);

            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.replace(request, retryPolicyInstance).map(response -> toResourceResponse(response, Trigger.class));

        } catch (Exception e) {
            logger.debug("Failure in replacing a Trigger due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Trigger>> deleteTrigger(String triggerLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteTriggerInternal(triggerLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Trigger>> deleteTriggerInternal(String triggerLink, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            if (StringUtils.isEmpty(triggerLink)) {
                throw new IllegalArgumentException("triggerLink");
            }

            logger.debug("Deleting a Trigger. triggerLink [{}]", triggerLink);
            String path = Utils.joinPath(triggerLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
                    ResourceType.Trigger, path, requestHeaders, options);

            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, Trigger.class));

        } catch (Exception e) {
            logger.debug("Failure in deleting a Trigger due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Trigger>> readTrigger(String triggerLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readTriggerInternal(triggerLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Trigger>> readTriggerInternal(String triggerLink, RequestOptions options,
                                                                      DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            if (StringUtils.isEmpty(triggerLink)) {
                throw new IllegalArgumentException("triggerLink");
            }

            logger.debug("Reading a Trigger. triggerLink [{}]", triggerLink);
            String path = Utils.joinPath(triggerLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.Trigger, path, requestHeaders, options);

            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, Trigger.class));

        } catch (Exception e) {
            logger.debug("Failure in reading a Trigger due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<Trigger>> readTriggers(String collectionLink, FeedOptions options) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        return readFeed(options, ResourceType.Trigger, Trigger.class,
                Utils.joinPath(collectionLink, Paths.TRIGGERS_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<Trigger>> queryTriggers(String collectionLink, String query,
                                                           FeedOptions options) {
        return queryTriggers(collectionLink, new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<Trigger>> queryTriggers(String collectionLink, SqlQuerySpec querySpec,
                                                           FeedOptions options) {
        return createQuery(collectionLink, querySpec, options, Trigger.class, ResourceType.Trigger);
    }

    @Override
    public Mono<ResourceResponse<UserDefinedFunction>> createUserDefinedFunction(String collectionLink,
                                                                                       UserDefinedFunction udf, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> createUserDefinedFunctionInternal(collectionLink, udf, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<UserDefinedFunction>> createUserDefinedFunctionInternal(String collectionLink,
                                                                                                UserDefinedFunction udf, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {
            logger.debug("Creating a UserDefinedFunction. collectionLink [{}], udf id [{}]", collectionLink,
                    udf.getId());
            RxDocumentServiceRequest request = getUserDefinedFunctionRequest(collectionLink, udf, options,
                    OperationType.Create);
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.create(request, retryPolicyInstance).map(response -> toResourceResponse(response, UserDefinedFunction.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in creating a UserDefinedFunction due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<UserDefinedFunction>> upsertUserDefinedFunction(String collectionLink,
                                                                                       UserDefinedFunction udf, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> upsertUserDefinedFunctionInternal(collectionLink, udf, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<UserDefinedFunction>> upsertUserDefinedFunctionInternal(String collectionLink,
                                                                                                UserDefinedFunction udf, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {
            logger.debug("Upserting a UserDefinedFunction. collectionLink [{}], udf id [{}]", collectionLink,
                    udf.getId());
            RxDocumentServiceRequest request = getUserDefinedFunctionRequest(collectionLink, udf, options,
                    OperationType.Upsert);
            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.upsert(request, retryPolicyInstance).map(response -> toResourceResponse(response, UserDefinedFunction.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in upserting a UserDefinedFunction due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<UserDefinedFunction>> replaceUserDefinedFunction(UserDefinedFunction udf,
                                                                                        RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceUserDefinedFunctionInternal(udf, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<UserDefinedFunction>> replaceUserDefinedFunctionInternal(UserDefinedFunction udf,
                                                                                                 RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {
            if (udf == null) {
                throw new IllegalArgumentException("udf");
            }

            logger.debug("Replacing a UserDefinedFunction. udf id [{}]", udf.getId());
            validateResource(udf);

            String path = Utils.joinPath(udf.getSelfLink(), null);
            Map<String, String> requestHeaders = this.getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
                    ResourceType.UserDefinedFunction, path, udf, requestHeaders, options);

            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.replace(request, retryPolicyInstance).map(response -> toResourceResponse(response, UserDefinedFunction.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in replacing a UserDefinedFunction due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<UserDefinedFunction>> deleteUserDefinedFunction(String udfLink,
                                                                                       RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteUserDefinedFunctionInternal(udfLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<UserDefinedFunction>> deleteUserDefinedFunctionInternal(String udfLink,
                                                                                                RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {
            if (StringUtils.isEmpty(udfLink)) {
                throw new IllegalArgumentException("udfLink");
            }

            logger.debug("Deleting a UserDefinedFunction. udfLink [{}]", udfLink);
            String path = Utils.joinPath(udfLink, null);
            Map<String, String> requestHeaders = this.getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
                    ResourceType.UserDefinedFunction, path, requestHeaders, options);

            if (retryPolicyInstance != null){
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, UserDefinedFunction.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in deleting a UserDefinedFunction due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<UserDefinedFunction>> readUserDefinedFunction(String udfLink,
                                                                                     RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readUserDefinedFunctionInternal(udfLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<UserDefinedFunction>> readUserDefinedFunctionInternal(String udfLink,
                                                                                              RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        // we are using an observable factory here
        // observable will be created fresh upon subscription
        // this is to ensure we capture most up to date information (e.g.,
        // session)
        try {
            if (StringUtils.isEmpty(udfLink)) {
                throw new IllegalArgumentException("udfLink");
            }

            logger.debug("Reading a UserDefinedFunction. udfLink [{}]", udfLink);
            String path = Utils.joinPath(udfLink, null);
            Map<String, String> requestHeaders = this.getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.UserDefinedFunction, path, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, UserDefinedFunction.class));

        } catch (Exception e) {
            // this is only in trace level to capture what's going on
            logger.debug("Failure in reading a UserDefinedFunction due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<UserDefinedFunction>> readUserDefinedFunctions(String collectionLink,
                                                                                  FeedOptions options) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        return readFeed(options, ResourceType.UserDefinedFunction, UserDefinedFunction.class,
                Utils.joinPath(collectionLink, Paths.USER_DEFINED_FUNCTIONS_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<UserDefinedFunction>> queryUserDefinedFunctions(String collectionLink,
                                                                                   String query, FeedOptions options) {
        return queryUserDefinedFunctions(collectionLink, new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<UserDefinedFunction>> queryUserDefinedFunctions(String collectionLink,
                                                                                   SqlQuerySpec querySpec, FeedOptions options) {
        return createQuery(collectionLink, querySpec, options, UserDefinedFunction.class, ResourceType.UserDefinedFunction);
    }

    @Override
    public Mono<ResourceResponse<Conflict>> readConflict(String conflictLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readConflictInternal(conflictLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Conflict>> readConflictInternal(String conflictLink, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {

        try {
            if (StringUtils.isEmpty(conflictLink)) {
                throw new IllegalArgumentException("conflictLink");
            }

            logger.debug("Reading a Conflict. conflictLink [{}]", conflictLink);
            String path = Utils.joinPath(conflictLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.Conflict, path, requestHeaders, options);

            Mono<RxDocumentServiceRequest> reqObs = addPartitionKeyInformation(request, null, null, options);

            return reqObs.flatMap(req -> {
                if (retryPolicyInstance != null) {
                    retryPolicyInstance.onBeforeSendRequest(request);
                }
                return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, Conflict.class));
            });

        } catch (Exception e) {
            logger.debug("Failure in reading a Conflict due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<Conflict>> readConflicts(String collectionLink, FeedOptions options) {

        if (StringUtils.isEmpty(collectionLink)) {
            throw new IllegalArgumentException("collectionLink");
        }

        return readFeed(options, ResourceType.Conflict, Conflict.class,
                Utils.joinPath(collectionLink, Paths.CONFLICTS_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<Conflict>> queryConflicts(String collectionLink, String query,
                                                             FeedOptions options) {
        return queryConflicts(collectionLink, new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<Conflict>> queryConflicts(String collectionLink, SqlQuerySpec querySpec,
                                                             FeedOptions options) {
        return createQuery(collectionLink, querySpec, options, Conflict.class, ResourceType.Conflict);
    }

    @Override
    public Mono<ResourceResponse<Conflict>> deleteConflict(String conflictLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteConflictInternal(conflictLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Conflict>> deleteConflictInternal(String conflictLink, RequestOptions options,
                                                                          DocumentClientRetryPolicy retryPolicyInstance) {

        try {
            if (StringUtils.isEmpty(conflictLink)) {
                throw new IllegalArgumentException("conflictLink");
            }

            logger.debug("Deleting a Conflict. conflictLink [{}]", conflictLink);
            String path = Utils.joinPath(conflictLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
                    ResourceType.Conflict, path, requestHeaders, options);

            Mono<RxDocumentServiceRequest> reqObs = addPartitionKeyInformation(request, null, null, options);
            return reqObs.flatMap(req -> {
                if (retryPolicyInstance != null) {
                    retryPolicyInstance.onBeforeSendRequest(request);
                }

                return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, Conflict.class));
            });

        } catch (Exception e) {
            logger.debug("Failure in deleting a Conflict due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<User>> createUser(String databaseLink, User user, RequestOptions options) {
        DocumentClientRetryPolicy documentClientRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> createUserInternal(databaseLink, user, options, documentClientRetryPolicy), documentClientRetryPolicy);
    }

    private Mono<ResourceResponse<User>> createUserInternal(String databaseLink, User user, RequestOptions options, DocumentClientRetryPolicy documentClientRetryPolicy) {
        try {
            logger.debug("Creating a User. databaseLink [{}], user id [{}]", databaseLink, user.getId());
            RxDocumentServiceRequest request = getUserRequest(databaseLink, user, options, OperationType.Create);
            return this.create(request, documentClientRetryPolicy).map(response -> toResourceResponse(response, User.class));

        } catch (Exception e) {
            logger.debug("Failure in creating a User due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<User>> upsertUser(String databaseLink, User user, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> upsertUserInternal(databaseLink, user, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<User>> upsertUserInternal(String databaseLink, User user, RequestOptions options,
                                                                  DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            logger.debug("Upserting a User. databaseLink [{}], user id [{}]", databaseLink, user.getId());
            RxDocumentServiceRequest request = getUserRequest(databaseLink, user, options, OperationType.Upsert);
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.upsert(request, retryPolicyInstance).map(response -> toResourceResponse(response, User.class));

        } catch (Exception e) {
            logger.debug("Failure in upserting a User due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    private RxDocumentServiceRequest getUserRequest(String databaseLink, User user, RequestOptions options,
                                                    OperationType operationType) {
        if (StringUtils.isEmpty(databaseLink)) {
            throw new IllegalArgumentException("databaseLink");
        }
        if (user == null) {
            throw new IllegalArgumentException("user");
        }

        RxDocumentClientImpl.validateResource(user);

        String path = Utils.joinPath(databaseLink, Paths.USERS_PATH_SEGMENT);
        Map<String, String> requestHeaders = getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(operationType, ResourceType.User, path, user,
                requestHeaders, options);

        return request;
    }

    @Override
    public Mono<ResourceResponse<User>> replaceUser(User user, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceUserInternal(user, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<User>> replaceUserInternal(User user, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            if (user == null) {
                throw new IllegalArgumentException("user");
            }
            logger.debug("Replacing a User. user id [{}]", user.getId());
            RxDocumentClientImpl.validateResource(user);

            String path = Utils.joinPath(user.getSelfLink(), null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
                    ResourceType.User, path, user, requestHeaders, options);
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.replace(request, retryPolicyInstance).map(response -> toResourceResponse(response, User.class));

        } catch (Exception e) {
            logger.debug("Failure in replacing a User due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }


    public Mono<ResourceResponse<User>> deleteUser(String userLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance =  this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deleteUserInternal(userLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<User>> deleteUserInternal(String userLink, RequestOptions options,
                                                                  DocumentClientRetryPolicy retryPolicyInstance) {

        try {
            if (StringUtils.isEmpty(userLink)) {
                throw new IllegalArgumentException("userLink");
            }
            logger.debug("Deleting a User. userLink [{}]", userLink);
            String path = Utils.joinPath(userLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
                    ResourceType.User, path, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, User.class));

        } catch (Exception e) {
            logger.debug("Failure in deleting a User due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }
    @Override
    public Mono<ResourceResponse<User>> readUser(String userLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readUserInternal(userLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<User>> readUserInternal(String userLink, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            if (StringUtils.isEmpty(userLink)) {
                throw new IllegalArgumentException("userLink");
            }
            logger.debug("Reading a User. userLink [{}]", userLink);
            String path = Utils.joinPath(userLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.User, path, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, User.class));

        } catch (Exception e) {
            logger.debug("Failure in reading a User due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<User>> readUsers(String databaseLink, FeedOptions options) {

        if (StringUtils.isEmpty(databaseLink)) {
            throw new IllegalArgumentException("databaseLink");
        }

        return readFeed(options, ResourceType.User, User.class,
                Utils.joinPath(databaseLink, Paths.USERS_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<User>> queryUsers(String databaseLink, String query, FeedOptions options) {
        return queryUsers(databaseLink, new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<User>> queryUsers(String databaseLink, SqlQuerySpec querySpec,
                                                     FeedOptions options) {
        return createQuery(databaseLink, querySpec, options, User.class, ResourceType.User);
    }

    @Override
    public Mono<ResourceResponse<Permission>> createPermission(String userLink, Permission permission,
                                                                     RequestOptions options) {
        DocumentClientRetryPolicy documentClientRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> createPermissionInternal(userLink, permission, options, documentClientRetryPolicy), this.resetSessionTokenRetryPolicy.getRequestPolicy());
    }

    private Mono<ResourceResponse<Permission>> createPermissionInternal(String userLink, Permission permission,
                                                                              RequestOptions options, DocumentClientRetryPolicy documentClientRetryPolicy) {

        try {
            logger.debug("Creating a Permission. userLink [{}], permission id [{}]", userLink, permission.getId());
            RxDocumentServiceRequest request = getPermissionRequest(userLink, permission, options,
                    OperationType.Create);
            return this.create(request, documentClientRetryPolicy).map(response -> toResourceResponse(response, Permission.class));

        } catch (Exception e) {
            logger.debug("Failure in creating a Permission due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Permission>> upsertPermission(String userLink, Permission permission,
                                                                     RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> upsertPermissionInternal(userLink, permission, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Permission>> upsertPermissionInternal(String userLink, Permission permission,
                                                                              RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {

        try {
            logger.debug("Upserting a Permission. userLink [{}], permission id [{}]", userLink, permission.getId());
            RxDocumentServiceRequest request = getPermissionRequest(userLink, permission, options,
                    OperationType.Upsert);
            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.upsert(request, retryPolicyInstance).map(response -> toResourceResponse(response, Permission.class));

        } catch (Exception e) {
            logger.debug("Failure in upserting a Permission due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    private RxDocumentServiceRequest getPermissionRequest(String userLink, Permission permission,
                                                          RequestOptions options, OperationType operationType) {
        if (StringUtils.isEmpty(userLink)) {
            throw new IllegalArgumentException("userLink");
        }
        if (permission == null) {
            throw new IllegalArgumentException("permission");
        }

        RxDocumentClientImpl.validateResource(permission);

        String path = Utils.joinPath(userLink, Paths.PERMISSIONS_PATH_SEGMENT);
        Map<String, String> requestHeaders = getRequestHeaders(options);
        RxDocumentServiceRequest request = RxDocumentServiceRequest.create(operationType, ResourceType.Permission, path,
                permission, requestHeaders, options);

        return request;
    }

    @Override
    public Mono<ResourceResponse<Permission>> replacePermission(Permission permission, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replacePermissionInternal(permission, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Permission>> replacePermissionInternal(Permission permission, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            if (permission == null) {
                throw new IllegalArgumentException("permission");
            }
            logger.debug("Replacing a Permission. permission id [{}]", permission.getId());
            RxDocumentClientImpl.validateResource(permission);

            String path = Utils.joinPath(permission.getSelfLink(), null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
                    ResourceType.Permission, path, permission, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.replace(request, retryPolicyInstance).map(response -> toResourceResponse(response, Permission.class));

        } catch (Exception e) {
            logger.debug("Failure in replacing a Permission due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Permission>> deletePermission(String permissionLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> deletePermissionInternal(permissionLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Permission>> deletePermissionInternal(String permissionLink, RequestOptions options,
                                                                              DocumentClientRetryPolicy retryPolicyInstance) {

        try {
            if (StringUtils.isEmpty(permissionLink)) {
                throw new IllegalArgumentException("permissionLink");
            }
            logger.debug("Deleting a Permission. permissionLink [{}]", permissionLink);
            String path = Utils.joinPath(permissionLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Delete,
                    ResourceType.Permission, path, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.delete(request, retryPolicyInstance).map(response -> toResourceResponse(response, Permission.class));

        } catch (Exception e) {
            logger.debug("Failure in deleting a Permission due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Permission>> readPermission(String permissionLink, RequestOptions options) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readPermissionInternal(permissionLink, options, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Permission>> readPermissionInternal(String permissionLink, RequestOptions options, DocumentClientRetryPolicy retryPolicyInstance ) {
        try {
            if (StringUtils.isEmpty(permissionLink)) {
                throw new IllegalArgumentException("permissionLink");
            }
            logger.debug("Reading a Permission. permissionLink [{}]", permissionLink);
            String path = Utils.joinPath(permissionLink, null);
            Map<String, String> requestHeaders = getRequestHeaders(options);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.Permission, path, requestHeaders, options);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }
            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, Permission.class));

        } catch (Exception e) {
            logger.debug("Failure in reading a Permission due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<Permission>> readPermissions(String userLink, FeedOptions options) {

        if (StringUtils.isEmpty(userLink)) {
            throw new IllegalArgumentException("userLink");
        }

        return readFeed(options, ResourceType.Permission, Permission.class,
                Utils.joinPath(userLink, Paths.PERMISSIONS_PATH_SEGMENT));
    }

    @Override
    public Flux<FeedResponse<Permission>> queryPermissions(String userLink, String query,
                                                                 FeedOptions options) {
        return queryPermissions(userLink, new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<Permission>> queryPermissions(String userLink, SqlQuerySpec querySpec,
                                                                 FeedOptions options) {
        return createQuery(userLink, querySpec, options, Permission.class, ResourceType.Permission);
    }

    @Override
    public Mono<ResourceResponse<Offer>> replaceOffer(Offer offer) {
        DocumentClientRetryPolicy documentClientRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> replaceOfferInternal(offer, documentClientRetryPolicy), documentClientRetryPolicy);
    }

    private Mono<ResourceResponse<Offer>> replaceOfferInternal(Offer offer, DocumentClientRetryPolicy documentClientRetryPolicy) {
        try {
            if (offer == null) {
                throw new IllegalArgumentException("offer");
            }
            logger.debug("Replacing an Offer. offer id [{}]", offer.getId());
            RxDocumentClientImpl.validateResource(offer);

            String path = Utils.joinPath(offer.getSelfLink(), null);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Replace,
                    ResourceType.Offer, path, offer, null, null);
            return this.replace(request, documentClientRetryPolicy).map(response -> toResourceResponse(response, Offer.class));

        } catch (Exception e) {
            logger.debug("Failure in replacing an Offer due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<ResourceResponse<Offer>> readOffer(String offerLink) {
        DocumentClientRetryPolicy retryPolicyInstance = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> readOfferInternal(offerLink, retryPolicyInstance), retryPolicyInstance);
    }

    private Mono<ResourceResponse<Offer>> readOfferInternal(String offerLink, DocumentClientRetryPolicy retryPolicyInstance) {
        try {
            if (StringUtils.isEmpty(offerLink)) {
                throw new IllegalArgumentException("offerLink");
            }
            logger.debug("Reading an Offer. offerLink [{}]", offerLink);
            String path = Utils.joinPath(offerLink, null);
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.Offer, path, (HashMap<String, String>)null, null);

            if (retryPolicyInstance != null) {
                retryPolicyInstance.onBeforeSendRequest(request);
            }

            return this.read(request, retryPolicyInstance).map(response -> toResourceResponse(response, Offer.class));

        } catch (Exception e) {
            logger.debug("Failure in reading an Offer due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    @Override
    public Flux<FeedResponse<Offer>> readOffers(FeedOptions options) {
        return readFeed(options, ResourceType.Offer, Offer.class,
                Utils.joinPath(Paths.OFFERS_PATH_SEGMENT, null));
    }

    private <T extends Resource> Flux<FeedResponse<T>> readFeedCollectionChild(FeedOptions options, ResourceType resourceType,
                                                                                     Class<T> klass, String resourceLink) {
        if (options == null) {
            options = new FeedOptions();
        }

        int maxPageSize = options.maxItemCount() != null ? options.maxItemCount() : -1;

        final FeedOptions finalFeedOptions = options;
        RequestOptions requestOptions = new RequestOptions();
        requestOptions.setPartitionKey(options.partitionKey());
        BiFunction<String, Integer, RxDocumentServiceRequest> createRequestFunc = (continuationToken, pageSize) -> {
            Map<String, String> requestHeaders = new HashMap<>();
            if (continuationToken != null) {
                requestHeaders.put(HttpConstants.HttpHeaders.CONTINUATION, continuationToken);
            }
            requestHeaders.put(HttpConstants.HttpHeaders.PAGE_SIZE, Integer.toString(pageSize));
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.ReadFeed,
                    resourceType, resourceLink, requestHeaders, finalFeedOptions);
            return request;
        };

        Function<RxDocumentServiceRequest, Mono<FeedResponse<T>>> executeFunc = request -> {
            return ObservableHelper.inlineIfPossibleAsObs(() -> {
                Mono<Utils.ValueHolder<DocumentCollection>> collectionObs = this.collectionCache.resolveCollectionAsync(request);
                Mono<RxDocumentServiceRequest> requestObs = this.addPartitionKeyInformation(request, null, null, requestOptions, collectionObs);

                return requestObs.flatMap(req -> this.readFeed(req)
                        .map(response -> toFeedResponsePage(response, klass)));
            }, this.resetSessionTokenRetryPolicy.getRequestPolicy());
        };

        return Paginator.getPaginatedQueryResultAsObservable(options, createRequestFunc, executeFunc, klass, maxPageSize);
    }

    private <T extends Resource> Flux<FeedResponse<T>> readFeed(FeedOptions options, ResourceType resourceType, Class<T> klass, String resourceLink) {
        if (options == null) {
            options = new FeedOptions();
        }

        int maxPageSize = options.maxItemCount() != null ? options.maxItemCount() : -1;
        final FeedOptions finalFeedOptions = options;
        BiFunction<String, Integer, RxDocumentServiceRequest> createRequestFunc = (continuationToken, pageSize) -> {
            Map<String, String> requestHeaders = new HashMap<>();
            if (continuationToken != null) {
                requestHeaders.put(HttpConstants.HttpHeaders.CONTINUATION, continuationToken);
            }
            requestHeaders.put(HttpConstants.HttpHeaders.PAGE_SIZE, Integer.toString(pageSize));
            RxDocumentServiceRequest request =  RxDocumentServiceRequest.create(OperationType.ReadFeed,
                    resourceType, resourceLink, requestHeaders, finalFeedOptions);
            return request;
        };

        Function<RxDocumentServiceRequest, Mono<FeedResponse<T>>> executeFunc = request -> {
            return ObservableHelper.inlineIfPossibleAsObs(() -> readFeed(request).map(response -> toFeedResponsePage(response, klass)),
                    this.resetSessionTokenRetryPolicy.getRequestPolicy());
        };

        return Paginator.getPaginatedQueryResultAsObservable(options, createRequestFunc, executeFunc, klass, maxPageSize);
    }

    @Override
    public Flux<FeedResponse<Offer>> queryOffers(String query, FeedOptions options) {
        return queryOffers(new SqlQuerySpec(query), options);
    }

    @Override
    public Flux<FeedResponse<Offer>> queryOffers(SqlQuerySpec querySpec, FeedOptions options) {
        return createQuery(null, querySpec, options, Offer.class, ResourceType.Offer);
    }

    @Override
    public Mono<DatabaseAccount> getDatabaseAccount() {
        DocumentClientRetryPolicy documentClientRetryPolicy = this.resetSessionTokenRetryPolicy.getRequestPolicy();
        return ObservableHelper.inlineIfPossibleAsObs(() -> getDatabaseAccountInternal(documentClientRetryPolicy), documentClientRetryPolicy);
    }

    private Mono<DatabaseAccount> getDatabaseAccountInternal(DocumentClientRetryPolicy documentClientRetryPolicy) {
        try {
            logger.debug("Getting Database Account");
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.DatabaseAccount, "", // path
                    (HashMap<String, String>) null,
                    null);
            return this.read(request, documentClientRetryPolicy).map(response -> toDatabaseAccount(response));

        } catch (Exception e) {
            logger.debug("Failure in getting Database Account due to [{}]", e.getMessage(), e);
            return Mono.error(e);
        }
    }

    public Object getSession() {
        return this.sessionContainer;
    }

    public void setSession(Object sessionContainer) {
        this.sessionContainer = (SessionContainer) sessionContainer;
    }

    public RxPartitionKeyRangeCache getPartitionKeyRangeCache() {
        return partitionKeyRangeCache;
    }

    public Flux<DatabaseAccount> getDatabaseAccountFromEndpoint(URI endpoint) {
        return Flux.defer(() -> {
            RxDocumentServiceRequest request = RxDocumentServiceRequest.create(OperationType.Read,
                    ResourceType.DatabaseAccount, "", null, (Object) null);
            this.populateHeaders(request, HttpConstants.HttpMethods.GET);

            request.setEndpointOverride(endpoint);
            return this.gatewayProxy.processMessage(request).doOnError(e -> {
                String message = String.format("Failed to retrieve database account information. %s",
                        e.getCause() != null
                                ? e.getCause().toString()
                                : e.toString());
                logger.warn(message);
            }).map(rsp -> rsp.getResource(DatabaseAccount.class))
                    .doOnNext(databaseAccount -> {
                        this.useMultipleWriteLocations = this.connectionPolicy.getUsingMultipleWriteLocations()
                                && BridgeInternal.isEnableMultipleWriteLocations(databaseAccount);
                    });
        });
    }

    /**
     * Certain requests must be routed through gateway even when the client connectivity mode is direct.
     *
     * @param request
     * @return RxStoreModel
     */
    private RxStoreModel getStoreProxy(RxDocumentServiceRequest request) {
        // If a request is configured to always use GATEWAY mode(in some cases when targeting .NET Core)
        // we return the GATEWAY store model
        if (request.UseGatewayMode) {
            return this.gatewayProxy;
        }

        ResourceType resourceType = request.getResourceType();
        OperationType operationType = request.getOperationType();

        if (resourceType == ResourceType.Offer ||
                resourceType.isScript() && operationType != OperationType.ExecuteJavaScript ||
                resourceType == ResourceType.PartitionKeyRange) {
            return this.gatewayProxy;
        }

        if (operationType == OperationType.Create
                || operationType == OperationType.Upsert) {
            if (resourceType == ResourceType.Database ||
                    resourceType == ResourceType.User ||
                    resourceType == ResourceType.DocumentCollection ||
                    resourceType == ResourceType.Permission) {
                return this.gatewayProxy;
            } else {
                return this.storeModel;
            }
        } else if (operationType == OperationType.Delete) {
            if (resourceType == ResourceType.Database ||
                    resourceType == ResourceType.User ||
                    resourceType == ResourceType.DocumentCollection) {
                return this.gatewayProxy;
            } else {
                return this.storeModel;
            }
        } else if (operationType == OperationType.Replace) {
            if (resourceType == ResourceType.DocumentCollection) {
                return this.gatewayProxy;
            } else {
                return this.storeModel;
            }
        } else if (operationType == OperationType.Read) {
            if (resourceType == ResourceType.DocumentCollection) {
                return this.gatewayProxy;
            } else {
                return this.storeModel;
            }
        } else {
            if ((request.getOperationType() == OperationType.Query || request.getOperationType() == OperationType.SqlQuery) &&
                    Utils.isCollectionChild(request.getResourceType())) {
                if (request.getPartitionKeyRangeIdentity() == null) {
                    return this.gatewayProxy;
                }
            }

            return this.storeModel;
        }
    }

    @Override
    public void close() {
        logger.info("Shutting down ...");
        logger.info("Closing Global Endpoint Manager ...");
        LifeCycleUtils.closeQuietly(this.globalEndpointManager);
        logger.info("Closing StoreClientFactory ...");
        LifeCycleUtils.closeQuietly(this.storeClientFactory);
        logger.info("Shutting down reactorHttpClient ...");
        try {
            this.reactorHttpClient.shutdown();
        } catch (Exception e) {
            logger.warn("shutting down reactorHttpClient failed", e);
        }
        logger.info("Shutting down completed.");
    }
}
