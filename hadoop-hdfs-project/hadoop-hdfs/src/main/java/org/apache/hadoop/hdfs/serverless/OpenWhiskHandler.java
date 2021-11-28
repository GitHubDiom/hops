package org.apache.hadoop.hdfs.serverless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mysql.clusterj.ClusterJHelper;
import io.hops.metrics.TransactionEvent;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.hadoop.hdfs.server.namenode.INode;
import org.apache.hadoop.hdfs.server.namenode.ServerlessNameNode;
import org.apache.hadoop.hdfs.serverless.operation.DuplicateRequest;
import org.apache.hadoop.hdfs.serverless.operation.FileSystemTaskUtils;
import org.apache.hadoop.hdfs.serverless.operation.NameNodeResult;
import org.apache.hadoop.hdfs.serverless.tcpserver.ServerlessHopsFSClient;
import org.apache.hadoop.util.Time;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apache.hadoop.hdfs.DFSConfigKeys.SERVERLESS_TCP_SERVER_PORT_DEFAULT;
import static org.apache.hadoop.hdfs.serverless.ServerlessNameNodeKeys.FORCE_REDO;

/**
 * This class encapsulates the behavior and functionality of the serverless function handler for OpenWhisk.
 *
 * The handler itself is actually just a single function, but there are lots of things that this handler does,
 * and it is cleaner to encapsulate all of this into a single class than to include it in the
 * ServerlessNameNode class directly, as was done previously.
 *
 * This is used on the NameNode side (obviously).
 */
public class OpenWhiskHandler {
    public static final Logger LOG = LoggerFactory.getLogger(OpenWhiskHandler.class.getName());

    /**
     * Some transactions are performed while creating the NameNode. Obviously the NameNode does not exist until
     * it is finished being created, so we store the TransactionEvent instances from those transactions here.
     * Once the NameNode is created, we add these events to the NameNode instance.
     */
    public static ThreadLocal<Set<TransactionEvent>> temporaryEventSet = new ThreadLocal<>();

    /**
     * Used internally to determine whether this instance is warm or cold.
     */
    private static boolean isCold = true;

    public static AtomicInteger activeRequestCounter = new AtomicInteger(0);

    /**
     * OpenWhisk handler.
     */
    public static JsonObject main(JsonObject args) {
        long startTime = System.nanoTime();
        String functionName = platformSpecificInitialization();

        LOG.info("============================================================");
        LOG.info(functionName + " v" + ServerlessNameNode.versionNumber + " received HTTP request.");
        int activeRequests = activeRequestCounter.incrementAndGet();
        LOG.info("Active HTTP requests: " + activeRequests);
        LOG.info("============================================================\n");

        performStaticInitialization();

        // The arguments passed by the user are included under the 'value' key.
        JsonObject userArguments = args.get(ServerlessNameNodeKeys.VALUE).getAsJsonObject();

        String clientIpAddress = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.CLIENT_INTERNAL_IP).getAsString();

        boolean tcpEnabled = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.TCP_ENABLED).getAsBoolean();

        Instant start = Instant.now();

        String[] commandLineArguments;
        // Attempt to extract the command-line arguments, which will be passed as a single string parameter.
        if (userArguments.has(ServerlessNameNodeKeys.COMMAND_LINE_ARGS)) {
            try {
                commandLineArguments = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.COMMAND_LINE_ARGS)
                        .getAsString().split("\\s+");
            } catch (ClassCastException ex) {
                // If it was included as a JsonArray, then unpack it that way.
                JsonArray commandLineArgumentsJson = userArguments.getAsJsonArray(
                        ServerlessNameNodeKeys.COMMAND_LINE_ARGS);
                commandLineArguments = new String[commandLineArgumentsJson.size()];

                // Unpack the arguments.
                for (int i = 0; i < commandLineArgumentsJson.size(); i++) {
                    commandLineArguments[i] = commandLineArgumentsJson.get(i).getAsString();
                }
            }
        }
        else {
            // Empty arguments.
            commandLineArguments = new String[0];
        }

        boolean debugEnabled = false;
        String debugString = null;

        // Check if NDB debugging is enabled. If so, then attempt to extract the dbug string. If
        // NDB debugging is enabled and a dbug string was extracted, then pass it to the ClusterJ API,
        // which will in turn pass the dbug string to the underlying NDB API.
        if (userArguments.has(ServerlessNameNodeKeys.DEBUG_NDB))
            debugEnabled = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.DEBUG_NDB).getAsBoolean();

        if (debugEnabled && userArguments.has(ServerlessNameNodeKeys.DEBUG_STRING_NDB))
            debugString = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.DEBUG_STRING_NDB).getAsString();

        if (debugEnabled && debugString != null) {
            LOG.debug("NDB debugging has been enabled. Using dbug string \"" +
                    debugString + "\"");

            // I am not sure if we need to make sure this is not called concurrently, but just in case...
            synchronized (OpenWhiskHandler.class) {
                ClusterJHelper.newDbug().push(debugString);
            }

            LOG.debug("Also enabling ClusterJ debug logging...");
            LogManager.getLogger("com.mysql.clusterj").setLevel(Level.DEBUG);
        } else {
            LOG.debug("NDB debugging is NOT enabled.");
        }

        // The name of the client. This originates from the DFSClient class.
        String clientName = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.CLIENT_NAME).getAsString();

        // The name of the file system operation that the client wants us to perform.
        String operation = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.OPERATION).getAsString();

        // This is NOT the OpenWhisk activation ID. The request ID originates at the client who invoked us.
        // That way, the corresponding TCP request (to this HTTP request) would have the same request ID.
        // This is used to prevent duplicate requests from being processed.
        String requestId = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.REQUEST_ID).getAsString();

        // The arguments to the file system operation.
        JsonObject fsArgs = userArguments.getAsJsonObject(ServerlessNameNodeKeys.FILE_SYSTEM_OP_ARGS);

        int tcpPort = -1;
        if (userArguments.has(ServerlessNameNodeKeys.TCP_PORT))
            tcpPort = userArguments.getAsJsonPrimitive(ServerlessNameNodeKeys.TCP_PORT).getAsInt();

        // Flag that indicates whether this action was invoked by a client or a DataNode.
        boolean isClientInvoker = userArguments.getAsJsonPrimitive(
                ServerlessNameNodeKeys.IS_CLIENT_INVOKER).getAsBoolean();
        String invokerIdentity = userArguments.getAsJsonPrimitive(
                ServerlessNameNodeKeys.INVOKER_IDENTITY).getAsString();

        LOG.info("=-=-=-=-=-=-= Serverless Function Information =-=-=-=-=-=-=");
        LOG.debug("Top-level OpenWhisk arguments: " + args);
        LOG.debug("User-passed OpenWhisk arguments: " + userArguments);
        LOG.info("Serverless function name: " + functionName);
        LOG.info("Invoked by: " + invokerIdentity);
        LOG.info("Client's name: " + clientName);
        LOG.info("Client IP address: " + (clientIpAddress == null ? "N/A" : clientIpAddress));
        LOG.info("Function container was " + (isCold ? "COLD" : "WARM") + ".");
        LOG.info("- - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -");
        LOG.info("Operation name: " + operation);
        LOG.debug("Operation arguments: " + fsArgs);
        LOG.info("=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=\n");

        LOG.debug("Handing control over to driver() function after "
                + DurationFormatUtils.formatDurationHMS((Duration.between(start, Instant.now()).toMillis())));

        // Execute the desired operation. Capture the result to be packaged and returned to the user.
        NameNodeResult result = driver(operation, fsArgs, commandLineArguments, functionName, clientIpAddress,
                requestId, clientName, isClientInvoker, tcpEnabled, tcpPort);

        // Set the `isCold` flag to false given this is now a warm container.
        isCold = false;

        long endTime = System.nanoTime();
        double timeElapsed = (endTime - startTime) / 1000000.0;

        LOG.debug("Returning back to client. Time elapsed: " + timeElapsed + " milliseconds.");
        LOG.debug("ServerlessNameNode is exiting now...");
        activeRequestCounter.decrementAndGet();
        result.setFnEndTime(Time.getUtcTime());
        return createJsonResponse(result);
    }

    /**
     * Assert that the container was cold prior to the currently-running activation.
     *
     * If the container is warm, then this will create a new IllegalStateException and add it to the
     * parameterized `result` object so that the client that invoked this function can see the exception
     * and process it accordingly.
     * @param result The result to be returned to the client.
     * @return True if the function was cold (as is expected when this function is called). Otherwise, false will be
     * returned, indicating that the function is warm (which is bad in the case that this function is called).
     */
    private static boolean assertIsCold(NameNodeResult result) {
        if (!isCold) {
            // Create and add the exception to the result so that it will be returned to the client.
            IllegalStateException ex = new IllegalStateException("Expected container to be cold, but it is warm.");
            result.addException(ex);

            return false;
        }

        return true;
    }

    /**
     * Executes the NameNode code/operation/function execution.
     * @param op The name of the FS operation to be performed.
     * @param fsArgs The arguments to be passed to the desired FS operation.
     * @param commandLineArguments The command-line arguments to be consumed by the NameNode during its creation.
     * @param functionName The name of this particular OpenWhisk action (i.e., serverless function).
     * @param clientIPAddress The IP address of the client who invoked this OpenWhisk action.
     * @param requestId The request ID for this particular action activation. This is NOT the OpenWhisk activation ID.
     *                  The request ID originates at the client who invoked us. That way, the corresponding TCP request
     *                  (to this HTTP request) would have the same request ID. This is used to prevent duplicate
     *                  requests from being processed.
     * @param clientName The name of the client who invoked this OpenWhisk action. Comes from the DFSClient class.
     * @param isClientInvoker Flag which indicates whether this activation was invoked by a client or a DataNode.
     * @param tcpPort The TCP port that the client who invoked us is using for their TCP server.
     *                If the client is using multiple HopsFS client threads at the same time, then there will
     *                presumably be multiple TCP servers, each listening on a different TCP port.
     * @return Result of executing NameNode code/operation/function execution.
     */
    private static NameNodeResult driver(String op, JsonObject fsArgs, String[] commandLineArguments,
                                     String functionName, String clientIPAddress, String requestId,
                                     String clientName, boolean isClientInvoker, boolean tcpEnabled,
                                     int tcpPort) {
        NameNodeResult result = new NameNodeResult(ServerlessNameNode.getFunctionNumberFromFunctionName(functionName),
                requestId, "HTTP", -1);

        long creationStart = Time.getUtcTime();
        LOG.debug("======== Getting or Creating Serverless NameNode Instance ========");

        // The very first step is to obtain a reference to the singleton ServerlessNameNode instance.
        // If this container was cold prior to this invocation, then we'll actually be creating the instance now.
        ServerlessNameNode serverlessNameNode;
        try {
            serverlessNameNode = ServerlessNameNode.getOrCreateNameNodeInstance(commandLineArguments,
                    functionName, isCold);
        }
        catch (Exception ex) {
            LOG.error("Encountered " + ex.getClass().getSimpleName()
                    + " while creating and initializing the NameNode: ", ex);
            result.addException(ex);
            return result;
        }

        long creationEnd = Time.getUtcTime();
        long creationDuration = creationEnd - creationStart;
        LOG.debug("Obtained NameNode instance with ID=" + serverlessNameNode.getId());
        result.setNameNodeId(serverlessNameNode.getId());
        LOG.debug("Getting/creating NN instance took: "
                + DurationFormatUtils.formatDurationHMS(creationDuration));

        // Some transactions are performed while creating the NameNode. Obviously the NameNode does not exist until
        // it is finished being created, so we store the TransactionEvent instances from those transactions in the
        // temporaryEventsSet variable. So, we just check here if it is not null. If not, then we add the events
        // to the NameNode (that was presumably just created). Then we clear the list.
        Set<TransactionEvent> tempEvents = temporaryEventSet.get();
        if (tempEvents != null) {
            serverlessNameNode.addTransactionEvents(tempEvents);
            tempEvents.clear();
        }

        // Check for duplicate requests. If the request is NOT a duplicate, then have the NameNode check for updates
        // from intermediate storage.
        LOG.debug("==================================================================");

        serverlessNameNode.tryStartWorkerThread();

        // Check if we need to redo this operation. This can occur if the TCP connection that was supposed
        // to deliver the result back to the client was dropped before the client received the result.
        boolean redoEvenIfDuplicate = false;
        if (fsArgs.has(FORCE_REDO) && fsArgs.get(FORCE_REDO).getAsBoolean())
            redoEvenIfDuplicate = true;

        // Check to see if this is a duplicate request, in which case we should return a message indicating as such.
        if (!redoEvenIfDuplicate && serverlessNameNode.checkIfRequestProcessedAlready(requestId)) {
            LOG.warn("This request (" + requestId + ") has already been received. Exiting now...");
            result.addResult(new DuplicateRequest("HTTP", requestId), true);
            return result;
        } else if (redoEvenIfDuplicate) {
            LOG.warn("Apparently, request " + requestId + " must be re-executed...");
        }

        // Finally, create a new task and assign it to the worker thread.
        // After this, we will simply wait for the result to be completed before returning it to the user.
        Future<Serializable> newTask = null;
        try {
            newTask = FileSystemTaskUtils.createAndEnqueueFileSystemTask(requestId, op, fsArgs, result,
                    serverlessNameNode, redoEvenIfDuplicate);

            // We wait for the task to finish executing in a separate try-catch block so that, if there is
            // an exception, then we can log a specific message indicating where the exception occurred. If we
            // waited for the task in this block, we wouldn't be able to indicate in the log whether the
            // exception occurred when creating/scheduling the task or while waiting for it to complete.
        } catch (Exception ex) {
            LOG.error("Error encountered while creating file system task "
                    + requestId + " for operation " + op + ":", ex);
            result.addException(ex);
        }

        result.setFnStartTime(creationStart);
        result.setEnqueuedTime(Time.getUtcTime());

        // Wait for the worker thread to execute the task. We'll return the result (if there is one) to the client.
        try {
            // If we failed to create a new task for the desired file system operation, then we'll just throw
            // another exception indicating that we have nothing to execute, seeing as the task doesn't exist.
            if (newTask == null) {
                throw new IllegalStateException("Failed to create task for operation " + op);
            }

            // In the case that we do have a task to wait on, we'll wait for the configured amount of time for the
            // worker thread to execute the task. If the task times out, then an exception will be thrown, caught,
            // and ultimately reported to the client. Alternatively, if the task is executed successfully, then
            // the future will resolve, and we'll be able to return a result to the client!
            LOG.debug("Waiting for task " + requestId + " (operation = " + op + ") to be executed now...");
            Serializable fileSystemOperationResult = newTask.get(
                    serverlessNameNode.getWorkerThreadTimeoutMs(), TimeUnit.MILLISECONDS);

            LOG.debug("Adding result from operation " + op + " to response for request " + requestId);
            if (fileSystemOperationResult instanceof NameNodeResult) {
                LOG.debug("Merging NameNodeResult instances now...");
                result.mergeInto((NameNodeResult)fileSystemOperationResult, false);
            } else if (fileSystemOperationResult != null) {
                LOG.warn("Worker thread returned result of type: "
                        + fileSystemOperationResult.getClass().getSimpleName());
                result.addResult(fileSystemOperationResult, true);
            } else {
                // This will be caught immediately and added to the result returned to the client.
                throw new IllegalStateException("Did not receive a result from the execution of task " + requestId);
            }
        } catch (Exception ex) {
            LOG.error("Encountered " + ex.getClass().getSimpleName() + " while waiting for task " + requestId
                    + " to be executed by the worker thread: ", ex);
            result.addException(ex);
        }

        try {
            // Check if a function mapping should be created and returned to the client.
            tryCreateFunctionMapping(result, fsArgs, serverlessNameNode);
        } catch (IOException ex) {
            LOG.error("Encountered IOException while trying to create function mapping:", ex);
            result.addException(ex);
        }

        // The last step is to establish a TCP connection to the client that invoked us.
        if (isClientInvoker && tcpEnabled) {
            ServerlessHopsFSClient serverlessHopsFSClient = new ServerlessHopsFSClient(
                    clientName, clientIPAddress, tcpPort);

            try {
                serverlessNameNode.getNameNodeTcpClient().addClient(serverlessHopsFSClient);
            } catch (IOException ex) {
                result.addException(ex);
            }
        } else if (!tcpEnabled) // Just so we can print a debug message indicating that we're not doing TCP.
            LOG.debug("TCP is DISABLED. Will not try to connect to the client.");

        result.logResultDebugInformation(op);
        return result;
    }

    /**
     * Check if there is a single target file/directory specified within the file system arguments passed by the client.
     * If there is, then we will create a function mapping for the client, so they know which NameNode is responsible
     * for caching the metadata associated with that particular file/directory.
     * @param result The current NameNodeResult, which will ultimately be returned to the user.
     * @param fsArgs The file system operation arguments passed in by the client (i.e., the individual who invoked us).
     * @param serverlessNameNode The current serverless name node instance, as we need this to determine the mapping.
     */
    public static void tryCreateFunctionMapping(NameNodeResult result,
                                                JsonObject fsArgs,
                                                ServerlessNameNode serverlessNameNode)
            throws IOException {
        // After performing the desired FS operation, we check if there is a particular file or directory
        // identified by the `src` parameter. This is the file/directory that should be hashed to a particular
        // serverless function. We calculate this here and include the information for the client in our response.
        if (fsArgs != null && fsArgs.has(ServerlessNameNodeKeys.SRC)) {
            String src = fsArgs.getAsJsonPrimitive(ServerlessNameNodeKeys.SRC).getAsString();

            INode inode = null;
            try {
                synchronized (serverlessNameNode) {
                    inode = serverlessNameNode.getINodeForCache(src);
                }
            }
            catch (IOException ex) {
                LOG.error("Encountered IOException while retrieving INode associated with target directory "
                        + src + ": ", ex);
                result.addException(ex);
            }

            // If we just deleted this INode, then it will presumably be null, so we need to check that it is not null.
            if (inode != null) {
                LOG.debug("Parent INode ID for '" + src + "': " + inode.getParentId());

                int functionNumber = serverlessNameNode.getMappedServerlessFunction(src);

                LOG.debug("Consistently hashed parent INode ID " + inode.getParentId()
                        + " to serverless function " + functionNumber);

                result.addFunctionMapping(src, inode.getParentId(), functionNumber);
            }
        }
    }

    /**
     * Create and return the response to return to whoever invoked this Serverless NameNode.
     * @param result The result returned from by `driver()`.
     * @return JsonObject to return as result of this OpenWhisk activation (i.e., serverless function execution).
     */
    private static JsonObject createJsonResponse(NameNodeResult result) {
        JsonObject resultJson = result.toJson(null, ServerlessNameNode.
                tryGetNameNodeInstance(true).
                getNamesystem().
                getMetadataCache());

        JsonObject response = new JsonObject();
        JsonObject headers = new JsonObject();
        headers.addProperty("content-type", "application/json");

        // TODO: We cannot gauge whether or not a request was successful simply on the basis of whether there is/isn't
        //       a result and if there are or are not any exceptions. Certain FS operations return nothing, meaning
        //       there wouldn't be a result. And the NN can encounter exceptions but still succeed. So for now, we'll
        //       just always return a statusCode 200, but eventually we may want to create a more robust system that
        //       uses status codes to indicate failures.

        response.addProperty("statusCode", 200);
        response.addProperty("status", "success");
        response.addProperty("success", true);

        // Only indicate that this failed if there is no result and there are exceptions.
        // If there are exceptions, but we managed to compute a result, then we'll consider it a success.
        /*if (result.getHasResult()) {
            response.addProperty("statusCode", 200);
            response.addProperty("status", "success");
            response.addProperty("success", true);
        }
        // No result and exceptions? Indicate a failure.
        else if (result.numExceptions() > 0) {
            response.addProperty("statusCode", 422);
            response.addProperty("status", "exception");
            response.addProperty("success", false);
        }*/
        // No result and no exception(s) means that there was probably just a duplicate request,
        // or an operation than doesn't return anything!
        /*else {
            // https://stackoverflow.com/a/3290369/5937661
            // "The request could not be completed due to a conflict with the current state of the resource."
            response.addProperty("statusCode", 409);
            response.addProperty("status", "unknown_failure");
            response.addProperty("success", false);
        }*/

        response.add("headers", headers);
        response.add("body", resultJson);
        return response;
    }

    /**
     * Perform some standard start-up procedures, set as setting certain environment variables.
     *
     * I am aware that static initialization blocks exist, but I prefer to use methods.
     */
    private static void performStaticInitialization() {
        System.setProperty("sun.io.serialization.extendedDebugInfo", "true");

        if (LOG.isDebugEnabled())
            LOG.info("Debug-logging IS enabled.");
        else
            LOG.info("Debug-logging is NOT enabled.");
    }

    /**
     * In this case, we are performing OpenWhisk-specific initialization.
     *
     * @return The name of this particular OpenWhisk serverless function/action. Note that the namespace portion
     * of the function's name is removed. So, if the function's fully-qualified name is "/whisk.system/namenode0",
     * then we return "namenode0", removing the "/whisk.system/" from the function's name.
     */
    private static String platformSpecificInitialization() {
        String activationId = System.getenv("__OW_ACTIVATION_ID");

        LOG.debug("Hadoop configuration directory: " + System.getenv("HADOOP_CONF_DIR"));
        LOG.debug("OpenWhisk activation ID: " + activationId);

        String functionNameWithNamespace = System.getenv("__OW_ACTION_NAME");
        Path functionNameAsPath = Paths.get(functionNameWithNamespace);

        // This will extract just the last part. The function names are really:
        // /whisk.system/namenodeX, where X is an integer. We don't want the "/whisk.system/" part.
        return functionNameAsPath.getFileName().toString();
    }
}
