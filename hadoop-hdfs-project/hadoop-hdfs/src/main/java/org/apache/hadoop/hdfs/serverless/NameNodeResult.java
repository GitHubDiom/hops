package org.apache.hadoop.hdfs.serverless;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.hdfs.server.namenode.FSDirectory;
import org.apache.hadoop.hdfs.server.namenode.ServerlessNameNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;

public class NameNodeResult {
    private static final Logger LOG = LoggerFactory.getLogger(NameNodeResult.class);

    /**
     * Exceptions encountered during the current request's execution.
     */
    private final ArrayList<Throwable> exceptions;

    /**
     * The desired result of the current request's execution.
     */
    private Object result;

    /**
     * We may be returning a mapping of a file or directory to a particular serverless function.
     */
    private ServerlessFunctionMapping serverlessFunctionMapping;

    public NameNodeResult() {
        exceptions = new ArrayList<Throwable>();
    }

    /**
     * Update the result field of this request's execution.
     * @param result The result of executing the desired operation.
     * @param forceOverwrite If true, overwrite an existing result.
     * @return True if the result was stored, otherwise false.
     */
    public boolean addResult(Object result, boolean forceOverwrite) {
        if (result == null || forceOverwrite) {
            this.result = result;
            return true;
        }

        return false;
    }

    /**
     * Store the file/directory-to-serverless-function mapping information so that it may be returned to
     * whoever invoked us.
     *
     * @param fileOrDirectory The file or directory being mapped to a function.
     * @param parentId The ID of the file or directory's parent iNode.
     * @param mappedFunctionName The name of the serverless function to which the file or directory was mapped.
     */
    public void addFunctionMapping(String fileOrDirectory, long parentId, String mappedFunctionName) {
        this.serverlessFunctionMapping = new ServerlessFunctionMapping(fileOrDirectory, parentId, mappedFunctionName);
    }

    /**
     * Formally record/take note that an exception occurred.
     * @param ex The exception that occurred.
     */
    public void addException(Exception ex) {
        exceptions.add(ex);
    }

    /**
     * Alias for `addException()`.
     * @param t The exception/throwable to record.
     */
    public void addThrowable(Throwable t) {
        exceptions.add(t);
    }

    /**
     * Log some useful debug information about the result field (e.g., the object's type) to the console.
     */
    public void logResultDebugInformation() {
        LOG.info("+-+-+-+-+-+-+ Result Debug Information +-+-+-+-+-+-+");
        LOG.info("Type: " + result.getClass());
        LOG.info("Result " + (result instanceof ServerlessNameNode ? "IS " : "is NOT ") + "serializable.");
        LOG.info("Value: " + result);
        LOG.info("+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+");
    }

    /**
     * Convert this object to a JsonObject so that it can be returned directly to the invoker.
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
            ObjectOutputStream objectOutputStream;
            objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
            objectOutputStream.writeObject(result);
            objectOutputStream.flush();

            byte[] objectBytes = byteArrayOutputStream.toByteArray();
            String base64Object = Base64.encodeBase64String(objectBytes);

            json.addProperty("base64result", base64Object);
        } catch (Exception ex) {
            LOG.error("Exception encountered whilst serializing result of file system operation: ", ex);
            addException(ex);
        }

        if (exceptions.size() > 0) {
            JsonArray exceptionsJson = new JsonArray();

            for (Throwable t : exceptions) {
                exceptionsJson.add(t.toString());
            }

            json.add("EXCEPTIONS", exceptionsJson);
        }

        return json;
    }

    /**
     * Encapsulates the mapping of a particular file or directory to a particular serverless function.
     */
    static class ServerlessFunctionMapping {
        /**
         * The file or directory that we're mapping to a serverless function.
         */
        String fileOrDirectory;

        /**
         * The ID of the file or directory's parent iNode.
         */
        long parentId;

        /**
         * The name of the serverless function to which the file or directory was mapped.
         */
        String mappedFunctionName;

        public ServerlessFunctionMapping(String fileOrDirectory, long parentId, String mappedFunctionName) {
            this.fileOrDirectory = fileOrDirectory;
            this.parentId = parentId;
            this.mappedFunctionName = mappedFunctionName;
        }
    }
}
