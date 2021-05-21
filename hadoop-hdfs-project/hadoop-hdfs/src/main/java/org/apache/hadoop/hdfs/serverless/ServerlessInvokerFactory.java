package org.apache.hadoop.hdfs.serverless;

import com.google.gson.JsonObject;
import io.hops.exception.StorageInitializtionException;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class ServerlessInvokerFactory {

    /**
     * Return the class name of the serverless invoker for the specified serverless platform.
     *
     * This automatically checks against the all-lowercase version of the `serverlessPlatformName` parameter.
     *
     * @throws StorageInitializtionException If the specified serverless platform is not supported (i.e., there does
     * not exist a serverless invoker implementation for the specified serverless platform).
     */
    private static String getInvokerClassName(String serverlessPlatformName) throws StorageInitializtionException {
        if (serverlessPlatformName.toLowerCase(Locale.ROOT).equals("openwhisk") ||
                serverlessPlatformName.toLowerCase(Locale.ROOT).equals("open whisk"))
            return "org.apache.hadoop.hdfs.serverless.OpenWhiskInvoker";
        else
            throw new StorageInitializtionException(
                    "Unsupported serverless platform specified: \"" + serverlessPlatformName + "\"");
    }

    /**
     * Return an instance of the specified serverless invoker class.
     */
    public static ServerlessInvoker<JsonObject> getServerlessInvoker(String serverlessPlatformName) throws StorageInitializtionException {
        String invokerClassName = getInvokerClassName(serverlessPlatformName);

        try {
            return (ServerlessInvoker<JsonObject>) Class.forName(invokerClassName).newInstance();
        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e1) {
            throw new StorageInitializtionException(e1);
        }
    }
}
