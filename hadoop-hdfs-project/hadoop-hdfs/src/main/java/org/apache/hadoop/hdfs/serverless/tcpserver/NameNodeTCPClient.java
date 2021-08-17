package org.apache.hadoop.hdfs.serverless.tcpserver;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import com.google.gson.JsonObject;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Encapsulates a Kryonet TCP client. Used to communicate directly with Serverless HopsFS clients.
 */
public class NameNodeTCPClient {
    private static final org.apache.commons.logging.Log LOG = LogFactory.getLog(NameNodeTCPClient.class);

    /**
     * This is the maximum amount of time a call to connect() will block. Calls to connect() occur when
     * establishing a connection to a new client.
     */
    private static final int CONNECTION_TIMEOUT = 5000;

    /**
     * Mapping from instances of ServerlessHopsFSClient to their associated TCP client object.
     */
    private final HashMap<ServerlessHopsFSClient, Client> tcpClients;

    /**
     * Queue of JsonObject objects sent from Serverless HopsFS clients to this NameNode.
     */
    private final ConcurrentLinkedQueue<JsonObject> workQueue;

    public NameNodeTCPClient() {
        tcpClients = new HashMap<>();
        workQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Register a new Serverless HopsFS client with the NameNode and establish a TCP connection with the new client.
     * @param newClient The new Serverless HopsFS client.
     * @return true if the connection was established successfully,
     * false if a connection with that client already exists.
     * @throws IOException If the connection to the new client times out.
     */
    public boolean addClient(ServerlessHopsFSClient newClient) throws IOException {
        if (tcpClients.containsKey(newClient)) {
            LOG.warn("NameNodeTCPClient already has a connection to client " + newClient);
            return false;
        }

        LOG.debug("Establishing connection with new Serverless HopsFS client " + newClient + " now...");

        // The call to connect() may produce an IOException if it times out.
        Client tcpClient = new Client();
        tcpClient.start();
        tcpClient.connect(CONNECTION_TIMEOUT, newClient.getClientIp(), newClient.getClientPort());

        // We need to register whatever classes will be serialized BEFORE any network activity is performed.
        registerClassesToBeTransferred(tcpClient.getKryo());

        tcpClient.addListener(new Listener() {
            public void received(Connection connection, Object object) {
                LOG.debug("Received message from connection " + connection.toString());

                // If we received a JsonObject, then add it to the queue for processing.
                if (object instanceof JsonObject) {
                    JsonObject args = (JsonObject)object;
                    workQueue.add(args);
                }
                else {
                    throw new IllegalArgumentException("Received object of unexpected type from client "
                            + tcpClient.toString() + ". Object type: " + object.getClass().getSimpleName() + ".");
                }
            }
        });

        tcpClients.put(newClient, tcpClient);

        return true;
    }

    /**
     * Unregister the given HopsFS client with the NameNode. Terminates the TCP connection to this client.
     * @param client The HopsFS client to unregister.
     * @return true if unregistered successfully, false if the client was already not registered with the NameNode.
     */
    public boolean removeClient(ServerlessHopsFSClient client) {
        Client tcpClient = tcpClients.getOrDefault(client, null);

        if (tcpClient == null) {
            LOG.warn("No TCP client associated with HopsFS client " + client.toString());
            return false;
        }

        // Stop the TCP client. This function calls the close() method.
        tcpClient.stop();

        tcpClients.remove(client);

        return true;
    }

    /**
     * @return The number of active TCP clients connected to the NameNode.
     */
    public int numClients() {
        return tcpClients.size();
    }

    public int workQueueSize() {
        return workQueue.size();
    }

    /**
     * Return the work queue object (which is of type ConcurrentLinkedQueue<JsonObject>).
     * @return the work queue object (which is of type ConcurrentLinkedQueue<JsonObject>).
     */
    public ConcurrentLinkedQueue<JsonObject> getWorkQueue() {
        return workQueue;
    }

    /**
     * Register all the classes that are going to be sent over the network.
     *
     * This must be done on both the client and the server before any network communication occurs.
     * The exact same classes are to be registered in the exact same order.
     * @param kryo The Kryo object obtained from a given Kryo TCP client/server via getKryo().
     */
    public static void registerClassesToBeTransferred(Kryo kryo) {
        // Register the JsonObject class with the Kryo serializer, as this is the object
        // that clients will use to invoke operations on the NN via TCP requests.
        kryo.register(JsonObject.class);
    }
}
