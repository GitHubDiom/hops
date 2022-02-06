package org.apache.hadoop.hdfs.serverless.invoking;

import com.google.gson.JsonObject;
import io.hops.leader_election.node.SortedActiveNodeList;
import io.hops.metadata.hdfs.entity.EncodingPolicy;
import io.hops.metadata.hdfs.entity.EncodingStatus;
import io.hops.metadata.hdfs.entity.MetaStatus;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoProtocolVersion;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.namenode.SafeModeException;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorageReport;
import org.apache.hadoop.hdfs.serverless.ServerlessNameNodeKeys;
import io.hops.metrics.OperationPerformed;
import org.apache.hadoop.hdfs.serverless.tcpserver.HopsFSUserServer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.ObjectWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.ExponentialBackOff;
import org.apache.hadoop.util.Time;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.hadoop.hdfs.DFSConfigKeys.*;
import static org.apache.hadoop.hdfs.DFSConfigKeys.SERVERLESS_PLATFORM_DEFAULT;
import static org.apache.hadoop.hdfs.serverless.ServerlessNameNodeKeys.*;

/**
 * This serves as an adapter between the DFSClient interface and the serverless NameNode API.
 *
 * This basically enables the DFSClient code to remain unmodified; it just issues its commands
 * to an instance of this class, which transparently handles the serverless invoking code.
 */
public class ServerlessNameNodeClient implements ClientProtocol {

    public static final Log LOG = LogFactory.getLog(ServerlessNameNodeClient.class);

    /**
     * Responsible for invoking the Serverless NameNode(s).
     */
    public ServerlessInvokerBase<JsonObject> serverlessInvoker;

    /**
     * Issue HTTP requests to this to invoke serverless functions.
     *
     * This is the BASE endpoint, meaning we must append a number to the end of it to reach
     * an actual function. This is because functions are named as PREFIX1, PREFIX2, ..., where
     * PREFIX is user-specified/user-configured.
     */
    public String serverlessEndpointBase;

    /**
     * The name of the serverless platform being used for the Serverless NameNodes.
     */
    public String serverlessPlatformName;

    private final DFSClient dfsClient;

    private final HopsFSUserServer tcpServer;

    /**
     * Number of unique deployments.
     */
    private final int numDeployments;

    /**
     * Flag that dictates whether TCP requests can be used to perform FS operations.
     */
    private final boolean tcpEnabled;

    /**
     * The server port defaults to whatever is specified in the configuration. But if multiple threads are used,
     * then conflicts will arise. So, the threads will try using different ports until one works (incrementing
     * the one specified in configuration until one works). We communicate this port to NameNodes in the invocation
     * payload so that they know how to connect to us.
     */
    private int tcpServerPort;

    /**
     * Indicates whether we're being executed in a local container for testing/profiling/debugging purposes.
     */
    private final boolean localMode;

    /**
     * The log level argument to be passed to serverless functions.
     */
    protected String serverlessFunctionLogLevel = "DEBUG";

    /**
     * Passed to serverless functions. Determines whether they execute the consistency protocol.
     */
    protected boolean consistencyProtocolEnabled = true;

    /**
     * For debugging, keep track of the operations we've performed.
     */
    private HashMap<String, OperationPerformed> operationsPerformed = new HashMap<>();

    public ServerlessNameNodeClient(Configuration conf, DFSClient dfsClient) throws IOException {
        // "https://127.0.0.1:443/api/v1/web/whisk.system/default/namenode?blocking=true";
        serverlessEndpointBase = dfsClient.serverlessEndpoint;
        serverlessPlatformName = conf.get(SERVERLESS_PLATFORM, SERVERLESS_PLATFORM_DEFAULT);
        tcpEnabled = conf.getBoolean(SERVERLESS_TCP_REQUESTS_ENABLED, SERVERLESS_TCP_REQUESTS_ENABLED_DEFAULT);
        localMode = conf.getBoolean(SERVERLESS_LOCAL_MODE, SERVERLESS_LOCAL_MODE_DEFAULT);

        if (localMode)
            numDeployments = 1;
        else
            numDeployments = conf.getInt(DFSConfigKeys.SERVERLESS_MAX_DEPLOYMENTS, DFSConfigKeys.SERVERLESS_MAX_DEPLOYMENTS_DEFAULT);

        LOG.info("Serverless endpoint: " + serverlessEndpointBase);
        LOG.info("Serverless platform: " + serverlessPlatformName);
        LOG.info("TCP requests are " + (tcpEnabled ? "enabled." : "disabled."));

        this.serverlessInvoker = dfsClient.serverlessInvoker;

        // This should already be set to true in the DFSClient class.
        this.serverlessInvoker.setIsClientInvoker(true);

        this.dfsClient = dfsClient;

        this.tcpServer = new HopsFSUserServer(conf, this);
        this.tcpServer.startServer();
    }

    public void setConsistencyProtocolEnabled(boolean enabled) {
        this.consistencyProtocolEnabled = enabled;
        this.serverlessInvoker.setConsistencyProtocolEnabled(enabled);
    }

    public void setServerlessFunctionLogLevel(String logLevel) {
        this.serverlessFunctionLogLevel = logLevel;
        this.serverlessInvoker.setServerlessFunctionLogLevel(logLevel);
    }

    public void printDebugInformation() {
        this.tcpServer.printDebugInformation();
    }

    /**
     * Issue a TCP request to the NameNode, instructing it to perform a certain operation.
     * This function will only issue a TCP request. If the TCP connection is lost mid-operation, then
     * the request is re-submitted via HTTP.
     *
     * @param operationName The name of the FS operation that the NameNode should perform.
     * @param opArguments The arguments to be passed to the specified file system operation.
     * @param targetDeployment The deployment number of the serverless NameNode deployment associated with the
     *                             target file or directory.
     * @return The response from the NameNode.
     */
    private JsonObject issueTCPRequest(String operationName,
                                       ArgumentContainer opArguments,
                                       int targetDeployment)
            throws InterruptedException, ExecutionException, IOException {
        long opStart = Time.getUtcTime();
        String requestId = UUID.randomUUID().toString();

        JsonObject payload = new JsonObject();
        payload.addProperty(ServerlessNameNodeKeys.REQUEST_ID, requestId);
        payload.addProperty(ServerlessNameNodeKeys.OPERATION, operationName);
        payload.addProperty(CONSISTENCY_PROTOCOL_ENABLED, consistencyProtocolEnabled);
        payload.addProperty(LOG_LEVEL, serverlessFunctionLogLevel);
        payload.add(ServerlessNameNodeKeys.FILE_SYSTEM_OP_ARGS, opArguments.convertToJsonObject());

        ExponentialBackOff exponentialBackOff = new ExponentialBackOff.Builder()
                .setMaximumRetries(5)
                .setInitialIntervalMillis(1000)
                .setMaximumIntervalMillis(5000)
                .setRandomizationFactor(0.50)
                .setMultiplier(2.0)
                .build();
        long backoffInterval = exponentialBackOff.getBackOffInMillis();
        while (backoffInterval >= 0) {
            JsonObject response;

            try {
                LOG.debug("Issuing TCP request for operation '" + operationName + "' now. Request ID = '" +
                        requestId + "'. Attempt " + exponentialBackOff.getNumberOfRetries() + "/" +
                        exponentialBackOff.getMaximumRetries() + ". Time elapsed so far: " +
                        (Time.getUtcTime() - opStart) + " ms.");
                response = tcpServer.issueTcpRequestAndWait(targetDeployment, false, payload,
                        serverlessInvoker.httpTimeoutMilliseconds);

                // After receiving a response, we need to check if it is a cancellation message or not.
                // Cancellation messages are posted by the TCP server if the TCP connection is terminated unexpectedly.
                // If we receive a cancellation message, then we need to fall back to HTTP. To do this, we'll just
                // throw an IOException here. It will be caught by the 'catch' clause, which will transparently
                // fall back to HTTP.
                if (response.has(CANCELLED) && response.get(CANCELLED).getAsBoolean()) {
                    // We add this argument so that the NameNode knows it must redo the operation,
                    // even though it has potentially seen it before.
                    opArguments.addPrimitive(FORCE_REDO, true);

                    // Throw the exception. This will be caught, and the request will be resubmitted via HTTP.
                    throw new IOException("The TCP future for request " + requestId + " (operation = " + operationName +
                            ") has been cancelled. Reason: " + response.get(REASON).getAsString() + ".");
                }

                if (response.has(DUPLICATE_REQUEST) && response.get(DUPLICATE_REQUEST).getAsBoolean()) {
                    LOG.warn("Received 'DUPLICATE REQUEST' notification via TCP for request " +
                            requestId + "...");

                    if (tcpServer.isFutureActive(requestId)) {
                        LOG.error("Request " + requestId +
                                " is still active, yet we received a 'DUPLICATE REQUEST' notification for it.");
                        LOG.warn("Resubmitting request " + requestId + " with FORCE_REDO...");

                        payload.get(FILE_SYSTEM_OP_ARGS).getAsJsonObject().addProperty(FORCE_REDO, true);

                        // We don't sleep in this case, as there wasn't a time-out exception or anything.
                        continue;
                    } else {
                      LOG.warn("Apparently we are not actually waiting on a result for request " + requestId +
                              "... Not resubmitting.");
                    }
                }

                long opEnd = Time.getUtcTime();

                // Collect and save/record metrics.
                long nameNodeId = response.get(ServerlessNameNodeKeys.NAME_NODE_ID).getAsLong();
                int cacheHits = response.get(ServerlessNameNodeKeys.CACHE_HITS).getAsInt();
                int cacheMisses = response.get(ServerlessNameNodeKeys.CACHE_MISSES).getAsInt();
                long fnStartTime = response.get(ServerlessNameNodeKeys.FN_START_TIME).getAsLong();
                long fnEndTime = response.get(ServerlessNameNodeKeys.FN_END_TIME).getAsLong();
                long enqueuedAt = response.get(ServerlessNameNodeKeys.ENQUEUED_TIME).getAsLong();
                long dequeuedAt = response.get(ServerlessNameNodeKeys.DEQUEUED_TIME).getAsLong();
                long finishedProcessingAt = response.get(PROCESSING_FINISHED_TIME).getAsLong();
                OperationPerformed operationPerformed = new OperationPerformed(operationName, requestId, opStart,
                        opEnd, enqueuedAt, dequeuedAt, fnStartTime, fnEndTime, targetDeployment, false,
                        true, "TCP", nameNodeId, cacheHits, cacheMisses);
                operationPerformed.setResultFinishedProcessingTime(finishedProcessingAt);
                operationsPerformed.put(requestId, operationPerformed);

                return response;
            } catch (TimeoutException ex) {
                LOG.error("Timed out while waiting for TCP response for request " + requestId + ".");
                LOG.error("Sleeping for " + backoffInterval + " seconds before retrying...");
                try {
                    Thread.sleep(backoffInterval);
                } catch (InterruptedException e) {
                    LOG.error("Encountered exception while sleeping during exponential backoff:", e);
                }

                backoffInterval = exponentialBackOff.getBackOffInMillis();
            } catch (IOException ex) {
                // There are two reasons an IOException may occur for which we can handle things "cleanly".
                //
                // The first is when we go to issue the TCP request and find that there are actually no available
                // connections. This can occur if the TCP connection(s) were closed in between when we checked if
                // any existed and when we went to actually issue the TCP request.
                //
                // The second is when the TCP connection is closed AFTER we have issued the request, but before we
                // receive a response from the NameNode for the request.
                //
                // In either scenario, we simply fall back to HTTP.
                LOG.error("Encountered IOException while trying to issue TCP request for operation " +
                        operationName + ":", ex);
                LOG.error("Falling back to HTTP instead. Time elapsed: " + (Time.getUtcTime() - opStart) + " ms.");

                // Issue the HTTP request. This function will handle retries and timeouts.
                response = dfsClient.serverlessInvoker.invokeNameNodeViaHttpPost(
                        operationName,
                        dfsClient.serverlessEndpoint,
                        null, // We do not have any additional/non-default arguments to pass to the NN.
                        opArguments,
                        requestId,
                        targetDeployment);

                long opEnd = Time.getUtcTime();
                LOG.debug("Received result from NameNode after falling back to HTTP for operation " +
                        operationName + ". Time elapsed: " + (opEnd - opStart) + ".");

                // Collect and save/record metrics.
                long nameNodeId = response.get(ServerlessNameNodeKeys.NAME_NODE_ID).getAsLong();
                int cacheHits = response.get(ServerlessNameNodeKeys.CACHE_HITS).getAsInt();
                int cacheMisses = response.get(ServerlessNameNodeKeys.CACHE_MISSES).getAsInt();
                long fnStartTime = response.get(ServerlessNameNodeKeys.FN_START_TIME).getAsLong();
                long fnEndTime = response.get(ServerlessNameNodeKeys.FN_END_TIME).getAsLong();
                long enqueuedAt = response.get(ServerlessNameNodeKeys.ENQUEUED_TIME).getAsLong();
                long dequeuedAt = response.get(ServerlessNameNodeKeys.DEQUEUED_TIME).getAsLong();
                long finishedProcessingAt = response.get(PROCESSING_FINISHED_TIME).getAsLong();
                OperationPerformed operationPerformed = new OperationPerformed(operationName, requestId, opStart,
                        opEnd, enqueuedAt, dequeuedAt, fnStartTime, fnEndTime, targetDeployment, true,
                        true, "HTTP", nameNodeId, cacheHits, cacheMisses);
                operationPerformed.setResultFinishedProcessingTime(finishedProcessingAt);
                operationsPerformed.put(requestId, operationPerformed);

                return response;
            }
        }

        return null;
    }

    /**
     * Perform an HTTP invocation of a serverless name node function concurrently with a TCP request to the same
     * Serverless NameNode, if a connection to that NameNode already exists. If no such connection exists, then only
     * the HTTP request will be issued.
     *
     * @param operationName The name of the FS operation that the NameNode should perform.
     * @param serverlessEndpoint The (base) OpenWhisk URI of the serverless NameNode(s).
     * @param nameNodeArguments The command-line arguments to be given to the NN, should it be created within the NN
     *                          function container (i.e., during a cold start).
     * @param opArguments The arguments to be passed to the specified file system operation.
     *
     * @return The result of executing the desired FS operation on the NameNode.
     */
    private JsonObject submitOperationToNameNode(
            String operationName,
            String serverlessEndpoint,
            HashMap<String, Object> nameNodeArguments,
            ArgumentContainer opArguments) throws IOException, InterruptedException, ExecutionException {
        // Check if there's a source directory parameter, as this is the file or directory that could
        // potentially be mapped to a serverless function.
        Object srcArgument = opArguments.get(ServerlessNameNodeKeys.SRC);

        // If tcpEnabled is false, we don't even bother checking to see if we can issue a TCP request.
        if (tcpEnabled && srcArgument instanceof String) {
            String sourceFileOrDirectory = (String)srcArgument;

            // Next, let's see if we have an entry in our cache for this file/directory.
            int mappedFunctionNumber = serverlessInvoker.getFunctionNumberForFileOrDirectory(sourceFileOrDirectory);

            // If there was indeed an entry, then we need to see if we have a connection to that NameNode.
            // If we do, then we'll concurrently issue a TCP request and an HTTP request to that NameNode.
            if (mappedFunctionNumber != -1 && tcpServer.connectionExists(mappedFunctionNumber)) {
                return issueTCPRequest(operationName, opArguments, mappedFunctionNumber);

                /*return issueConcurrentTcpHttpRequests(
                        operationName, serverlessEndpoint, nameNodeArguments, opArguments, mappedFunctionNumber);*/
            } else {
                LOG.debug("Source file/directory " + sourceFileOrDirectory + " is mapped to serverless NameNode " +
                        mappedFunctionNumber + ". TCP connection exists: " +
                        tcpServer.connectionExists(mappedFunctionNumber));
            }
        }

        LOG.debug("Issuing HTTP request only for operation " + operationName);

        String requestId = UUID.randomUUID().toString();

        Object srcObj = opArguments.get("src");
        String src = null;
        if (srcObj != null)
            src = (String)srcObj;

        int mappedFunctionNumber = (src != null) ? serverlessInvoker.cache.getFunction(src) : -1;

        long startTime = Time.getUtcTime();

        // If there is no "source" file/directory argument, or if there was no existing mapping for the given source
        // file/directory, then we'll just use an HTTP request.
        JsonObject response = dfsClient.serverlessInvoker.invokeNameNodeViaHttpPost(
                operationName,
                dfsClient.serverlessEndpoint,
                null, // We do not have any additional/non-default arguments to pass to the NN.
                opArguments,
                requestId,
                mappedFunctionNumber);

        LOG.debug("Response: " + response.toString());

        if (response.has("body"))
            response = response.get("body").getAsJsonObject();

        long nameNodeId = -1;
        if (response.has(ServerlessNameNodeKeys.NAME_NODE_ID))
            nameNodeId = response.get(ServerlessNameNodeKeys.NAME_NODE_ID).getAsLong();

        int deployment = mappedFunctionNumber;
        if (response.has(ServerlessNameNodeKeys.DEPLOYMENT_NUMBER))
            deployment = response.get(ServerlessNameNodeKeys.DEPLOYMENT_NUMBER).getAsInt();

        int cacheHits = response.get(ServerlessNameNodeKeys.CACHE_HITS).getAsInt();
        int cacheMisses = response.get(ServerlessNameNodeKeys.CACHE_MISSES).getAsInt();

        long fnStartTime = response.get(ServerlessNameNodeKeys.FN_START_TIME).getAsLong();
        long fnEndTime = response.get(ServerlessNameNodeKeys.FN_END_TIME).getAsLong();

        long enqueuedAt = response.get(ServerlessNameNodeKeys.ENQUEUED_TIME).getAsLong();
        long dequeuedAt = response.get(ServerlessNameNodeKeys.DEQUEUED_TIME).getAsLong();
        long finishedProcessingAt = response.get(PROCESSING_FINISHED_TIME).getAsLong();

        OperationPerformed operationPerformed
                = new OperationPerformed(operationName, requestId,
                startTime, Time.getUtcTime(), enqueuedAt, dequeuedAt, fnStartTime, fnEndTime,
                deployment, true, true,
                response.get(ServerlessNameNodeKeys.REQUEST_METHOD).getAsString(), nameNodeId, cacheMisses, cacheHits);
        operationPerformed.setResultFinishedProcessingTime(finishedProcessingAt);
        operationsPerformed.put(requestId, operationPerformed);

        return response;
    }

    /**
     * Return the operations performed by this client.
     */
    public List<OperationPerformed> getOperationsPerformed() {
        return new ArrayList<>(operationsPerformed.values());
    }

    /**
     * Clear the collection of operations performed.
     */
    public void clearOperationsPerformed() {
        this.operationsPerformed.clear();
    }

    public void addOperationPerformed(OperationPerformed operationPerformed) {
        operationsPerformed.put(operationPerformed.getRequestId(), operationPerformed);
    }

    public void addOperationPerformeds(Collection<OperationPerformed> operationPerformeds) {
        for (OperationPerformed op : operationPerformeds)
            operationsPerformed.put(op.getRequestId(), op);
    }

    /**
     * Return the list of the operations we've performed. This is just used for debugging purposes.
     */
    public void printOperationsPerformed() {
        List<OperationPerformed> opsPerformedList = new ArrayList<>(operationsPerformed.values());
        Collections.sort(opsPerformedList);

        String[] columnNames = {
          "Op Name", "Start Time", "End Time", "Duration (ms)", "Deployment", "HTTP", "TCP"
        };

        // Object[][] data = new Object[opsPerformedList.size()][];
        for (int i = 0; i < opsPerformedList.size(); i++) {
            OperationPerformed opPerformed = opsPerformedList.get(i);
            // data[i] = opPerformed.getAsArray();
        }

        System.out.println("====================== Operations Performed ======================");
        System.out.println("Number performed: " + operationsPerformed.size());
        System.out.println(OperationPerformed.getToStringHeader());
        for (OperationPerformed operationPerformed : opsPerformedList)
            System.out.println(operationPerformed.toString());

        System.out.println("\n-- SUMS ----------------------------------------------------------------------------------------------------------------------");
        System.out.println(OperationPerformed.getMetricsHeader());
        System.out.println(OperationPerformed.getMetricsString(OperationPerformed.getSums(opsPerformedList)));

        System.out.println("\n-- AVERAGES ------------------------------------------------------------------------------------------------------------------");
        System.out.println(OperationPerformed.getMetricsHeader());
        System.out.println(OperationPerformed.getMetricsString(OperationPerformed.getAverages(opsPerformedList)));

        System.out.println("\n-- REQUESTS PER DEPLOYMENT ---------------------------------------------------------------------------------------------------");
        HashMap<Integer, Integer> requestsPerDeployment = OperationPerformed.getRequestsPerDeployment(opsPerformedList);
        StringBuilder deploymentHeader = new StringBuilder();
        for (int i = 0; i < numDeployments; i++)
            deploymentHeader.append(i).append('\t');
        System.out.println(deploymentHeader);
        StringBuilder valuesString = new StringBuilder();
        for (int i = 0; i < numDeployments; i++) {
            int requests = requestsPerDeployment.getOrDefault(i, 0);
            valuesString.append(requests).append("\t");
        }
        System.out.println(valuesString);

        System.out.println("\n-- REQUESTS PER NAMENODE  ----------------------------------------------------------------------------------------------------");
        HashMap<Long, Integer> deploymentMapping = new HashMap<>();
        HashMap<Long, Integer> requestsPerNameNode = OperationPerformed.getRequestsPerNameNode(opsPerformedList, deploymentMapping);
        StringBuilder formatString = new StringBuilder();
        for (Long nameNodeId : requestsPerNameNode.keySet())
            formatString.append("%-25s ");

        String[] idsWithDeployment = new String[requestsPerNameNode.size()];
        int idx = 0;
        for (Long nameNodeId : requestsPerNameNode.keySet())
            idsWithDeployment[idx++] = nameNodeId + " (" + deploymentMapping.get(nameNodeId) + ")";

        System.out.println(String.format(formatString.toString(), idsWithDeployment));
        System.out.println(String.format(formatString.toString(), requestsPerNameNode.values().toArray()));

        System.out.println("\n==================================================================");
    }

    /**
     * Shuts down this client. Currently, the only steps taken during shut-down is the stopping of the TCP server.
     */
    public void stop() {
        LOG.debug("ServerlessNameNodeClient stopping now...");
        this.tcpServer.stop();
    }

    @Override
    public JsonObject latencyBenchmark(String connectionUrl, String dataSource, String query, int id) throws SQLException, IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public LocatedBlocks getBlockLocations(String src, long offset, long length) throws IOException {
        LocatedBlocks locatedBlocks = null;

        // Arguments for the 'create' filesystem operation.
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("offset", offset);
        opArguments.put("length", length);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getBlockLocations",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getBlockLocations to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getBlockLocations to NameNode.");
        }

        Object result =  serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            locatedBlocks = (LocatedBlocks)result;

        return locatedBlocks;
    }

    @Override
    public LocatedBlocks getMissingBlockLocations(String filePath) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void addBlockChecksum(String src, int blockIndex, long checksum) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public long getBlockChecksum(String src, int blockIndex) throws IOException {
        return 0;
    }

    @Override
    public FsServerDefaults getServerDefaults() throws IOException {
        FsServerDefaults serverDefaults = null;

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getServerDefaults",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    new ArgumentContainer());
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getServerDefaults to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getServerDefaults to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            serverDefaults = (FsServerDefaults)result;

        return serverDefaults;
    }

    @Override
    public HdfsFileStatus create(String src, FsPermission masked, String clientName, EnumSetWritable<CreateFlag> flag,
                                 boolean createParent, short replication, long blockSize,
                                 CryptoProtocolVersion[] supportedVersions, EncodingPolicy policy)
            throws IOException {
        // We need to pass a series of arguments to the Serverless NameNode. We prepare these arguments here
        // in a HashMap and pass them off to the ServerlessInvoker, which will package them up in the required
        // format for the Serverless NameNode.
        HdfsFileStatus stat = null;

        // Arguments for the 'create' filesystem operation.
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("masked", masked.toShort());
        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, dfsClient.clientName);

        // Convert this argument (to the 'create' function) to a String so we can send it over JSON.
        DataOutputBuffer out = new DataOutputBuffer();
        ObjectWritable.writeObject(out, flag, flag.getClass(), null);
        byte[] objectBytes = out.getData();
        String enumSetBase64 = Base64.encodeBase64String(objectBytes);

        opArguments.put("enumSetBase64", enumSetBase64);
        opArguments.put("createParent", createParent);
        LOG.warn("Using hard-coded replication value of 1.");
        opArguments.put("replication", 1);
        opArguments.put("blockSize", blockSize);

        // Include a flag to indicate whether or not the policy is non-null.
        opArguments.put("policyExists", policy != null);

        // Only include these if the policy is non-null.
        if (policy != null) {
            opArguments.put("codec", policy.getCodec());
            opArguments.put("targetReplication", policy.getTargetReplication());
        }

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "create",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation create to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation create to NameNode.");
        }

        // Extract the result from the Json response.
        // If there's an exception, then it will be logged by this function.
        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            stat = (HdfsFileStatus)result;

        return stat;
    }

    @Override
    public HdfsFileStatus create(String src, FsPermission masked, String clientName, EnumSetWritable<CreateFlag> flag,
                                 boolean createParent, short replication, long blockSize,
                                 CryptoProtocolVersion[] supportedVersions)
            throws AccessControlException, AlreadyBeingCreatedException, DSQuotaExceededException,
            FileAlreadyExistsException, FileNotFoundException, NSQuotaExceededException, ParentNotDirectoryException,
            SafeModeException, UnresolvedLinkException, IOException {
        return this.create(src, masked, clientName, flag, createParent, replication, blockSize, supportedVersions, null);
    }

    @Override
    public LastBlockWithStatus append(String src, String clientName, EnumSetWritable<CreateFlag> flag) throws AccessControlException, DSQuotaExceededException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        LastBlockWithStatus stat = null;

        // Arguments for the 'append' filesystem operation.
        ArgumentContainer opArguments = new ArgumentContainer();

        // Serialize the `EnumSetWritable<CreateFlag> flag` argument.
        DataOutputBuffer out = new DataOutputBuffer();
        ObjectWritable.writeObject(out, flag, flag.getClass(), null);
        byte[] objectBytes = out.getData();
        String enumSetBase64 = Base64.encodeBase64String(objectBytes);

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, clientName);
        opArguments.put(ServerlessNameNodeKeys.FLAG, enumSetBase64);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "append",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation append to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation append to NameNode.");
        }

        // Extract the result from the Json response.
        // If there's an exception, then it will be logged by this function.
        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            stat = (LastBlockWithStatus)result;

        return stat;
    }

    @Override
    public boolean setReplication(String src, short replication) throws AccessControlException, DSQuotaExceededException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        return false;
    }

    @Override
    public BlockStoragePolicy getStoragePolicy(byte storagePolicyID) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public BlockStoragePolicy[] getStoragePolicies() throws IOException {
        return new BlockStoragePolicy[0];
    }

    @Override
    public void setStoragePolicy(String src, String policyName) throws UnresolvedLinkException, FileNotFoundException, QuotaExceededException, IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void setMetaStatus(String src, MetaStatus metaStatus) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("metaStatus", metaStatus.ordinal());

        try {
            submitOperationToNameNode(
                    "setMetaStatus",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation setMetaStatus to NameNode:", ex);
        }
    }

    @Override
    public void setPermission(String src, FsPermission permission) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("permission", permission);

        try {
            submitOperationToNameNode(
                    "setPermission",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation setPermission to NameNode:", ex);
        }
    }

    @Override
    public void setOwner(String src, String username, String groupname) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("username", username);
        opArguments.put("groupname", groupname);

        try {
            submitOperationToNameNode(
                    "setOwner",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation setOwner to NameNode:", ex);
        }
    }

    @Override
    public void abandonBlock(ExtendedBlock b, long fileId, String src, String holder) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("holder", holder);
        opArguments.put("fileId", fileId);
        opArguments.put("b", b);

        try {
            submitOperationToNameNode(
                    "abandonBlock",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation abandonBlock to NameNode:", ex);
        }
    }

    @Override
    public LocatedBlock addBlock(String src, String clientName, ExtendedBlock previous, DatanodeInfo[] excludeNodes,
                                 long fileId, String[] favoredNodes) throws IOException, ClassNotFoundException {
        // HashMap<String, Object> opArguments = new HashMap<>();
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, clientName);
        opArguments.put("previous", previous);
        opArguments.put("fileId", fileId);
        opArguments.put("favoredNodes", favoredNodes);
        opArguments.put("excludeNodes", excludeNodes);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "addBlock",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation addBlock to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation addBlock to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);

        if (result != null) {
            LocatedBlock locatedBlock = (LocatedBlock) result;

            LOG.debug("Result returned from addBlock() is of type: " + result.getClass().getSimpleName());
            LOG.debug("LocatedBlock returned by addBlock(): " + locatedBlock);

            return locatedBlock;
        }

        return null;
    }

    @Override
    public LocatedBlock getAdditionalDatanode(String src, long fileId, ExtendedBlock blk, DatanodeInfo[] existings, String[] existingStorageIDs, DatanodeInfo[] excludes, int numAdditionalNodes, String clientName) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public boolean complete(String src, String clientName, ExtendedBlock last, long fileId, byte[] data) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, clientName);
        opArguments.put("last", last);
        opArguments.put("fileId", fileId);
        opArguments.put("data", data);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "complete",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation complete to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation complete to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (boolean)result;

        return true;
    }

    @Override
    public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public boolean rename(String src, String dst) throws UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("dst", dst);

        Integer[] optionsArr = new Integer[1];

        optionsArr[0] = 0; // 0 is the Options.Rename ordinal/value for `NONE`

        opArguments.put("options", optionsArr);

        try {
            submitOperationToNameNode(
                    "rename",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation rename to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation rename to NameNode.");
        }

        return true;
    }

    @Override
    public void concat(String trg, String[] srcs) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put("trg", trg);
        opArguments.put("srcs", srcs);

        try {
            submitOperationToNameNode(
                    "concat",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation concat to NameNode:", ex);
        }
    }

    @Override
    public void rename2(String src, String dst, Options.Rename... options) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("dst", dst);

        Integer[] optionsArr = new Integer[options.length];

        for (int i = 0; i < options.length; i++) {
            optionsArr[i] = options[i].ordinal();
        }

        opArguments.put("options", optionsArr);

        try {
            submitOperationToNameNode(
                    "rename", // Not rename2, we just map 'rename' to 'renameTo' or 'rename2' or whatever
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation rename to NameNode:", ex);
        }
    }

    @Override
    public boolean truncate(String src, long newLength, String clientName) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("newLength", newLength);
        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, clientName);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "truncate",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation truncate to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation truncate to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (boolean)result;

        return true;
    }

    @Override
    public boolean delete(String src, boolean recursive) throws AccessControlException, FileNotFoundException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("recursive", recursive);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "delete",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation delete to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation delete to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (boolean)result;

        return false;
    }

    @Override
    public boolean mkdirs(String src, FsPermission masked, boolean createParent) throws AccessControlException, FileAlreadyExistsException, FileNotFoundException, NSQuotaExceededException, ParentNotDirectoryException, SafeModeException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("masked", masked);
        opArguments.put("createParent", createParent);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "mkdirs",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation mkdirs to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation mkdirs to NameNode.");
        }

        Object res = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (res != null)
            return (boolean)res;

        throw new IOException("Received null response for mkdirs operation...");
    }

    @Override
    public DirectoryListing getListing(String src, byte[] startAfter, boolean needLocation) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);
        opArguments.put("startAfter", startAfter);
        opArguments.put("needLocation", needLocation);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getListing",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getListing to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getListing to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (DirectoryListing)result;

        return null;
    }

    @Override
    public void renewLease(String clientName) throws AccessControlException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.addPrimitive("clientName", clientName);

        try {
            submitOperationToNameNode(
                    "renewLease",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getListing to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getListing to NameNode.");
        }
    }

    @Override
    public boolean recoverLease(String src, String clientName) throws IOException {
        return false;
    }

    @Override
    public long[] getStats() throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getStats",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getListing to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getListing to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (long[])result;

        return null;
    }

    @Override
    public DatanodeInfo[] getDatanodeReport(HdfsConstants.DatanodeReportType type) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put("type", type.ordinal());

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getDatanodeReport",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getListing to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getListing to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (DatanodeInfo[])result;

        return null;
    }

    @Override
    public DatanodeStorageReport[] getDatanodeStorageReport(HdfsConstants.DatanodeReportType type) throws IOException {
        return new DatanodeStorageReport[0];
    }

    @Override
    public long getPreferredBlockSize(String filename) throws IOException, UnresolvedLinkException {
        return 0;
    }

    @Override
    public boolean setSafeMode(HdfsConstants.SafeModeAction action, boolean isChecked) throws IOException {
        return false;
    }

    @Override
    public void refreshNodes() throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public RollingUpgradeInfo rollingUpgrade(HdfsConstants.RollingUpgradeAction action) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public CorruptFileBlocks listCorruptFileBlocks(String path, String cookie) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void setBalancerBandwidth(long bandwidth) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public HdfsFileStatus getFileInfo(String src) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getFileInfo",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getFileInfo to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation getFileInfo to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (HdfsFileStatus)result;

        return null;
    }

    @Override
    public boolean isFileClosed(String src) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "isFileClosed",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation isFileClosed to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation isFileClosed to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (boolean)result;

        return false;
    }

    @Override
    public HdfsFileStatus getFileLinkInfo(String src) throws AccessControlException, UnresolvedLinkException, IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.SRC, src);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getFileLinkInfo",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation getFileLinkInfo to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation isFileClosed to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (HdfsFileStatus)result;

        return null;
    }

    @Override
    public ContentSummary getContentSummary(String path) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void setQuota(String path, long namespaceQuota, long storagespaceQuota, StorageType type) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {

    }

    @Override
    public void fsync(String src, long inodeId, String client, long lastBlockLength) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void setTimes(String src, long mtime, long atime) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void createSymlink(String target, String link, FsPermission dirPerm, boolean createParent) throws AccessControlException, FileAlreadyExistsException, FileNotFoundException, ParentNotDirectoryException, SafeModeException, UnresolvedLinkException, IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public String getLinkTarget(String path) throws AccessControlException, FileNotFoundException, IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public LocatedBlock updateBlockForPipeline(ExtendedBlock block, String clientName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, clientName);
        opArguments.put("block", block);

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "updateBlockForPipeline",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation complete to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation complete to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (LocatedBlock)result;

        return null;
    }

    @Override
    public void updatePipeline(String clientName, ExtendedBlock oldBlock, ExtendedBlock newBlock,
                               DatanodeID[] newNodes, String[] newStorages) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        opArguments.put(ServerlessNameNodeKeys.CLIENT_NAME, clientName);
        opArguments.put("oldBlock", oldBlock);
        opArguments.put("newBlock", newBlock);
        opArguments.put("newNodes", newNodes);
        opArguments.put("newStorages", newStorages);

        try {
            submitOperationToNameNode(
                    "updatePipeline",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation complete to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation complete to NameNode.");
        }
    }

    @Override
    public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public long renewDelegationToken(Token<DelegationTokenIdentifier> token) throws IOException {
        return 0;
    }

    @Override
    public void cancelDelegationToken(Token<DelegationTokenIdentifier> token) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public DataEncryptionKey getDataEncryptionKey() throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void ping() throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    /**
     * Attempt to pre-warm the NameNodes by pinging each deployment the specified number of times.
     * @param numPingsPerDeployment Number of times to ping the deployment.
     */
    @Override
    public void prewarm(int numPingsPerDeployment) throws IOException {
        Thread[] threads = new Thread[numDeployments];

        for (int deploymentNumber = 0; deploymentNumber < numDeployments; deploymentNumber++) {
            final int depNum = deploymentNumber;
            Thread thread = new Thread(() -> {
                LOG.debug("Invoking deployment " + depNum + " a total of " + numPingsPerDeployment + "x.");
                for (int i = 0; i < numPingsPerDeployment; i++) {
                    String requestId = UUID.randomUUID().toString();

                    // If there is no "source" file/directory argument, or if there was no existing mapping for the given source
                    // file/directory, then we'll just use an HTTP request.
                    try {
                        dfsClient.serverlessInvoker.invokeNameNodeViaHttpPost(
                                "ping",
                                dfsClient.serverlessEndpoint,
                                null, // We do not have any additional/non-default arguments to pass to the NN.
                                new ArgumentContainer(),
                                requestId,
                                depNum);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            });

            threads[deploymentNumber] = thread;
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread: threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void ping(int targetDeployment) throws IOException {
        String requestId = UUID.randomUUID().toString();

        // If there is no "source" file/directory argument, or if there was no existing mapping for the given source
        // file/directory, then we'll just use an HTTP request.
        dfsClient.serverlessInvoker.invokeNameNodeViaHttpPost(
                "ping",
                dfsClient.serverlessEndpoint,
                null, // We do not have any additional/non-default arguments to pass to the NN.
                new ArgumentContainer(),
                requestId,
                targetDeployment);
    }

    @Override
    public SortedActiveNodeList getActiveNamenodesForClient() throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();

        JsonObject responseJson;
        try {
            responseJson = submitOperationToNameNode(
                    "getActiveNamenodesForClient",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation complete to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation complete to NameNode.");
        }

        Object result = serverlessInvoker.extractResultFromJsonResponse(responseJson);
        if (result != null)
            return (SortedActiveNodeList)result;

        return null;
    }

    @Override
    public void changeConf(List<String> props, List<String> newVals) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public EncodingStatus getEncodingStatus(String filePath) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void encodeFile(String filePath, EncodingPolicy policy) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void revokeEncoding(String filePath, short replication) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public LocatedBlock getRepairedBlockLocations(String sourcePath, String parityPath, LocatedBlock block, boolean isParity) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void checkAccess(String path, FsAction mode) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public LastUpdatedContentSummary getLastUpdatedContentSummary(String path) throws AccessControlException, FileNotFoundException, UnresolvedLinkException, IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void modifyAclEntries(String src, List<AclEntry> aclSpec) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void removeAclEntries(String src, List<AclEntry> aclSpec) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void removeDefaultAcl(String src) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void removeAcl(String src) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void setAcl(String src, List<AclEntry> aclSpec) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public AclStatus getAclStatus(String src) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void createEncryptionZone(String src, String keyName) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public EncryptionZone getEZForPath(String src) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public BatchedRemoteIterator.BatchedEntries<EncryptionZone> listEncryptionZones(long prevId) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void setXAttr(String src, XAttr xAttr, EnumSet<XAttrSetFlag> flag) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public List<XAttr> getXAttrs(String src, List<XAttr> xAttrs) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public List<XAttr> listXAttrs(String src) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void removeXAttr(String src, XAttr xAttr) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public long addCacheDirective(CacheDirectiveInfo directive, EnumSet<CacheFlag> flags) throws IOException {
        return 0;
    }

    @Override
    public void modifyCacheDirective(CacheDirectiveInfo directive, EnumSet<CacheFlag> flags) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void removeCacheDirective(long id) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public BatchedRemoteIterator.BatchedEntries<CacheDirectiveEntry> listCacheDirectives(long prevId, CacheDirectiveInfo filter) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void addCachePool(CachePoolInfo info) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void modifyCachePool(CachePoolInfo req) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void removeCachePool(String pool) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public BatchedRemoteIterator.BatchedEntries<CachePoolEntry> listCachePools(String prevPool) throws IOException {
        throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void addUser(String userName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();
        opArguments.put("userName", userName);

        try {
            submitOperationToNameNode(
                    "addUser",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation addUser to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation addUser to NameNode.");
        }
    }

    @Override
    public void addGroup(String groupName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();
        opArguments.put("groupName", groupName);

        try {
            submitOperationToNameNode(
                    "addGroup",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation addGroup to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation addGroup to NameNode.");
        }
    }

    @Override
    public void addUserToGroup(String userName, String groupName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();
        opArguments.put("userName", userName);
        opArguments.put("groupName", groupName);

        try {
            submitOperationToNameNode(
                    "addUserToGroup",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation addUserToGroup to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation addUserToGroup to NameNode.");
        }
    }

    @Override
    public void removeUser(String userName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();
        opArguments.put("userName", userName);

        try {
            submitOperationToNameNode(
                    "removeUser",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation removeUser to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation removeUser to NameNode.");
        }
    }

    @Override
    public void removeGroup(String groupName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();
        opArguments.put("groupName", groupName);

        try {
            submitOperationToNameNode(
                    "removeGroup",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation removeGroup to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation removeGroup to NameNode.");
        }
    }

    @Override
    public void removeUserFromGroup(String userName, String groupName) throws IOException {
        ArgumentContainer opArguments = new ArgumentContainer();
        opArguments.put("userName", userName);
        opArguments.put("groupName", groupName);

        try {
            submitOperationToNameNode(
                    "removeUserFromGroup",
                    dfsClient.serverlessEndpoint,
                    null, // We do not have any additional/non-default arguments to pass to the NN.
                    opArguments);
        } catch (ExecutionException | InterruptedException ex) {
            LOG.error("Exception encountered while submitting operation removeUserFromGroup to NameNode:", ex);
            throw new IOException("Exception encountered while submitting operation removeUserFromGroup to NameNode.");
        }
    }

    @Override
    public void invCachesUserRemoved(String userName) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void invCachesGroupRemoved(String groupName) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void invCachesUserRemovedFromGroup(String userName, String groupName) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public void invCachesUserAddedToGroup(String userName, String groupName) throws IOException {
		throw new UnsupportedOperationException("Function has not yet been implemented.");
    }

    @Override
    public long getEpochMS() throws IOException {
        return 0;
    }

    public int getTcpServerPort() {
        return tcpServerPort;
    }

    /**
     * IMPORTANT: This function also calls setTcpServerPort() on the ServerlessInvoker instance.
     */
    public void setTcpServerPort(int tcpServerPort) {
        this.tcpServerPort = tcpServerPort;

        this.serverlessInvoker.setTcpPort(tcpServerPort);
    }
}
