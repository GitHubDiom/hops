/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

// Used for latency benchmark.
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mysql.clusterj.annotation.Column;
import com.mysql.clusterj.annotation.PersistenceCapable;
import com.mysql.clusterj.annotation.PrimaryKey;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.BlockingService;
import io.hops.leader_election.node.ActiveNode;
import io.hops.leader_election.node.SortedActiveNodeList;
import io.hops.metadata.hdfs.entity.EncodingPolicy;
import io.hops.metadata.hdfs.entity.EncodingStatus;
import io.hops.metadata.hdfs.entity.MetaStatus;
import io.hops.metadata.hdfs.entity.RetryCacheEntry;
import io.hops.security.UsersGroups;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.hadoop.HadoopIllegalArgumentException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.crypto.CryptoProtocolVersion;
import org.apache.hadoop.fs.BatchedRemoteIterator.BatchedEntries;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.*;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.HDFSPolicyProvider;
import org.apache.hadoop.hdfs.protocol.*;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.RollingUpgradeAction;
import org.apache.hadoop.hdfs.protocol.HdfsConstants.SafeModeAction;
import org.apache.hadoop.hdfs.protocol.proto.ClientNamenodeProtocolProtos.ClientNamenodeProtocol;
import org.apache.hadoop.hdfs.protocol.proto.DatanodeProtocolProtos.DatanodeProtocolService;
import org.apache.hadoop.hdfs.protocol.proto.NamenodeProtocolProtos.NamenodeProtocolService;
import org.apache.hadoop.hdfs.protocolPB.*;
import org.apache.hadoop.hdfs.security.token.block.DataEncryptionKey;
import org.apache.hadoop.hdfs.security.token.block.ExportedBlockKeys;
import org.apache.hadoop.hdfs.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.hdfs.server.blockmanagement.BRLoadBalancingOverloadException;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockManager;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.hdfs.server.namenode.metrics.NameNodeMetrics;
import org.apache.hadoop.hdfs.server.namenode.web.resources.NamenodeWebHdfsMethods;
import org.apache.hadoop.hdfs.server.protocol.*;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.*;
import org.apache.hadoop.ipc.proto.GenericRefreshProtocolProtos.GenericRefreshProtocolService;
import org.apache.hadoop.ipc.proto.RefreshCallQueueProtocolProtos.RefreshCallQueueProtocolService;
import org.apache.hadoop.ipc.protocolPB.GenericRefreshProtocolPB;
import org.apache.hadoop.ipc.protocolPB.GenericRefreshProtocolServerSideTranslatorPB;
import org.apache.hadoop.ipc.protocolPB.RefreshCallQueueProtocolPB;
import org.apache.hadoop.ipc.protocolPB.RefreshCallQueueProtocolServerSideTranslatorPB;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.Groups;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AuthorizationException;
import org.apache.hadoop.security.authorize.ProxyUsers;
import org.apache.hadoop.security.proto.RefreshAuthorizationPolicyProtocolProtos.RefreshAuthorizationPolicyProtocolService;
import org.apache.hadoop.security.proto.RefreshUserMappingsProtocolProtos.RefreshUserMappingsProtocolService;
import org.apache.hadoop.security.protocolPB.RefreshAuthorizationPolicyProtocolPB;
import org.apache.hadoop.security.protocolPB.RefreshAuthorizationPolicyProtocolServerSideTranslatorPB;
import org.apache.hadoop.security.protocolPB.RefreshUserMappingsProtocolPB;
import org.apache.hadoop.security.protocolPB.RefreshUserMappingsProtocolServerSideTranslatorPB;
import org.apache.hadoop.security.token.SecretManager.InvalidToken;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.tools.proto.GetUserMappingsProtocolProtos.GetUserMappingsProtocolService;
import org.apache.hadoop.tools.protocolPB.GetUserMappingsProtocolPB;
import org.apache.hadoop.tools.protocolPB.GetUserMappingsProtocolServerSideTranslatorPB;
import org.apache.hadoop.tracing.SpanReceiverInfo;
import org.apache.hadoop.tracing.TraceAdminPB.TraceAdminService;
import org.apache.hadoop.tracing.TraceAdminProtocolPB;
import org.apache.hadoop.tracing.TraceAdminProtocolServerSideTranslatorPB;
import org.apache.hadoop.util.VersionInfo;
import org.apache.hadoop.util.VersionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.*;
import java.util.*;

import static org.apache.hadoop.hdfs.DFSConfigKeys.*;
import static org.apache.hadoop.hdfs.protocol.HdfsConstants.MAX_PATH_DEPTH;
import static org.apache.hadoop.hdfs.protocol.HdfsConstants.MAX_PATH_LENGTH;
import static org.apache.hadoop.util.Time.now;

/**
 * This class is responsible for handling all of the RPC calls to the NameNode.
 * It is created, started, and stopped by {@link ServerlessNameNode}.
 */
public class NameNodeRpcServer implements NamenodeProtocols {
  
  private static final Logger LOG = LoggerFactory.getLogger(NameNodeRpcServer.class); //ServerlessNameNode.LOG;
  private static final Logger stateChangeLog = ServerlessNameNode.stateChangeLog;
  private static final Logger blockStateChangeLog = ServerlessNameNode
      .blockStateChangeLog;
  
  // Dependencies from other parts of NN.
  protected FSNamesystem namesystem;
  protected final ServerlessNameNode nn;
  private final NameNodeMetrics metrics;
  
  private final boolean serviceAuthEnabled;

  /**
   * The RPC server that listens to requests from DataNodes
   */
  private final RPC.Server serviceRpcServer;
  private final InetSocketAddress serviceRPCAddress;
  
  /**
   * The RPC server that listens to requests from clients
   */
  protected final RPC.Server clientRpcServer;
  protected final InetSocketAddress clientRpcAddress;
  
  private final String minimumDataNodeVersion;

  public NameNodeRpcServer(Configuration conf, ServerlessNameNode nn) throws IOException {
    this.nn = nn;
    this.namesystem = nn.getNamesystem();
    this.metrics = ServerlessNameNode.getNameNodeMetrics();

    int handlerCount = conf.getInt(DFS_NAMENODE_HANDLER_COUNT_KEY,
        DFS_NAMENODE_HANDLER_COUNT_DEFAULT);

    RPC.setProtocolEngine(conf, ClientNamenodeProtocolPB.class,
        ProtobufRpcEngine.class);

    ClientNamenodeProtocolServerSideTranslatorPB
        clientProtocolServerTranslator =
        new ClientNamenodeProtocolServerSideTranslatorPB(this);
    BlockingService clientNNPbService = ClientNamenodeProtocol.
        newReflectiveBlockingService(clientProtocolServerTranslator);
    
    DatanodeProtocolServerSideTranslatorPB dnProtoPbTranslator =
        new DatanodeProtocolServerSideTranslatorPB(this);
    BlockingService dnProtoPbService = DatanodeProtocolService
        .newReflectiveBlockingService(dnProtoPbTranslator);

    NamenodeProtocolServerSideTranslatorPB namenodeProtocolXlator =
        new NamenodeProtocolServerSideTranslatorPB(this);
    BlockingService NNPbService = NamenodeProtocolService
        .newReflectiveBlockingService(namenodeProtocolXlator);

    RefreshAuthorizationPolicyProtocolServerSideTranslatorPB
        refreshAuthPolicyXlator =
        new RefreshAuthorizationPolicyProtocolServerSideTranslatorPB(this);
    BlockingService refreshAuthService =
        RefreshAuthorizationPolicyProtocolService
            .newReflectiveBlockingService(refreshAuthPolicyXlator);

    RefreshUserMappingsProtocolServerSideTranslatorPB refreshUserMappingXlator =
        new RefreshUserMappingsProtocolServerSideTranslatorPB(this);
    BlockingService refreshUserMappingService =
        RefreshUserMappingsProtocolService
            .newReflectiveBlockingService(refreshUserMappingXlator);

    RefreshCallQueueProtocolServerSideTranslatorPB refreshCallQueueXlator = 
        new RefreshCallQueueProtocolServerSideTranslatorPB(this);
    BlockingService refreshCallQueueService = RefreshCallQueueProtocolService
        .newReflectiveBlockingService(refreshCallQueueXlator);

    GenericRefreshProtocolServerSideTranslatorPB genericRefreshXlator =
        new GenericRefreshProtocolServerSideTranslatorPB(this);
    BlockingService genericRefreshService = GenericRefreshProtocolService
        .newReflectiveBlockingService(genericRefreshXlator);

    GetUserMappingsProtocolServerSideTranslatorPB getUserMappingXlator = 
        new GetUserMappingsProtocolServerSideTranslatorPB(this);
    BlockingService getUserMappingService = GetUserMappingsProtocolService
        .newReflectiveBlockingService(getUserMappingXlator);

    TraceAdminProtocolServerSideTranslatorPB traceAdminXlator =
        new TraceAdminProtocolServerSideTranslatorPB(this);
    BlockingService traceAdminService = TraceAdminService
        .newReflectiveBlockingService(traceAdminXlator);
    
    WritableRpcEngine.ensureInitialized();

    InetSocketAddress serviceRpcAddr = nn.getServiceRpcServerAddress(conf);
    if (serviceRpcAddr != null) {
      String bindHost = nn.getServiceRpcServerBindHost(conf);
      if (bindHost == null) {
        bindHost = serviceRpcAddr.getHostName();
      }
      LOG.info("Service RPC server is binding to " + bindHost + ":" +
          serviceRpcAddr.getPort());

      int serviceHandlerCount =
          conf.getInt(DFS_NAMENODE_SERVICE_HANDLER_COUNT_KEY,
              DFS_NAMENODE_SERVICE_HANDLER_COUNT_DEFAULT);
      this.serviceRpcServer = new RPC.Builder(conf).setProtocol(
          org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB.class)
          .setInstance(clientNNPbService)
          .setBindAddress(bindHost)
          .setPort(serviceRpcAddr.getPort()).setNumHandlers(serviceHandlerCount)
          .setVerbose(false)
          .setSecretManager(namesystem.getDelegationTokenSecretManager())
          .build();

      // Add all the RPC protocols that the namenode implements
      DFSUtil.addPBProtocol(conf, NamenodeProtocolPB.class, NNPbService,
          serviceRpcServer);
      DFSUtil.addPBProtocol(conf, DatanodeProtocolPB.class, dnProtoPbService,
          serviceRpcServer);
      DFSUtil.addPBProtocol(conf, RefreshAuthorizationPolicyProtocolPB.class,
          refreshAuthService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, RefreshUserMappingsProtocolPB.class,
          refreshUserMappingService, serviceRpcServer);
      // We support Refreshing call queue here in case the client RPC queue is full
      DFSUtil.addPBProtocol(conf, RefreshCallQueueProtocolPB.class,
          refreshCallQueueService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, GenericRefreshProtocolPB.class,
          genericRefreshService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, GetUserMappingsProtocolPB.class, 
          getUserMappingService, serviceRpcServer);
      DFSUtil.addPBProtocol(conf, TraceAdminProtocolPB.class,
          traceAdminService, serviceRpcServer);

      // Update the address with the correct port
      InetSocketAddress listenAddr = serviceRpcServer.getListenerAddress();
      serviceRPCAddress = new InetSocketAddress(
            serviceRpcAddr.getHostName(), listenAddr.getPort());
      nn.setRpcServiceServerAddress(conf, serviceRPCAddress);
    } else {
      serviceRpcServer = null;
      serviceRPCAddress = null;
    }
    InetSocketAddress rpcAddr = nn.getRpcServerAddress(conf);
    String bindHost = nn.getRpcServerBindHost(conf);
    if (bindHost == null) {
      bindHost = rpcAddr.getHostName();
    }
    LOG.info("RPC server is binding to " + bindHost + ":" + rpcAddr.getPort());

    this.clientRpcServer = new RPC.Builder(conf).setProtocol(
        org.apache.hadoop.hdfs.protocolPB.ClientNamenodeProtocolPB.class)
        .setInstance(clientNNPbService).setBindAddress(bindHost)
        .setPort(rpcAddr.getPort()).setNumHandlers(handlerCount)
        .setVerbose(false)
        .setSecretManager(namesystem.getDelegationTokenSecretManager()).build();

    // Add all the RPC protocols that the namenode implements
    DFSUtil.addPBProtocol(conf, NamenodeProtocolPB.class, NNPbService,
        clientRpcServer);
    DFSUtil.addPBProtocol(conf, DatanodeProtocolPB.class, dnProtoPbService,
        clientRpcServer);
    DFSUtil.addPBProtocol(conf, RefreshAuthorizationPolicyProtocolPB.class,
        refreshAuthService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, RefreshUserMappingsProtocolPB.class,
        refreshUserMappingService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, RefreshCallQueueProtocolPB.class,
        refreshCallQueueService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, GenericRefreshProtocolPB.class,
        genericRefreshService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, GetUserMappingsProtocolPB.class, 
        getUserMappingService, clientRpcServer);
    DFSUtil.addPBProtocol(conf, TraceAdminProtocolPB.class,
        traceAdminService, clientRpcServer);

    // set service-level authorization security policy
    if (serviceAuthEnabled =
        conf.getBoolean(CommonConfigurationKeys.HADOOP_SECURITY_AUTHORIZATION,
            false)) {
      clientRpcServer.refreshServiceAcl(conf, new HDFSPolicyProvider());
      if (serviceRpcServer != null) {
        serviceRpcServer.refreshServiceAcl(conf, new HDFSPolicyProvider());
      }
    }

    // The rpc-server port can be ephemeral... ensure we have the correct info
    InetSocketAddress listenAddr = clientRpcServer.getListenerAddress();
      clientRpcAddress = new InetSocketAddress(
          rpcAddr.getHostName(), listenAddr.getPort());
    nn.setRpcServerAddress(conf, clientRpcAddress);
    
    minimumDataNodeVersion =
        conf.get(DFSConfigKeys.DFS_NAMENODE_MIN_SUPPORTED_DATANODE_VERSION_KEY,
            DFSConfigKeys.DFS_NAMENODE_MIN_SUPPORTED_DATANODE_VERSION_DEFAULT);
    
     // Set terse exception whose stack trace won't be logged
    clientRpcServer.addTerseExceptions(SafeModeException.class,
        FileNotFoundException.class,
        HadoopIllegalArgumentException.class,
        FileAlreadyExistsException.class,
        InvalidPathException.class,
        ParentNotDirectoryException.class,
        UnresolvedLinkException.class,
        AlreadyBeingCreatedException.class,
        QuotaExceededException.class,
        RecoveryInProgressException.class,
        AccessControlException.class,
        InvalidToken.class,
        LeaseExpiredException.class,
        NSQuotaExceededException.class,
        DSQuotaExceededException.class,
        QuotaByStorageTypeExceededException.class,
        AclException.class,
        FSLimitException.PathComponentTooLongException.class,
        FSLimitException.MaxDirectoryItemsExceededException.class,
        UnresolvedPathException.class,
        BRLoadBalancingOverloadException.class);
  }
  
  /** Allow access to the client RPC server for testing */
  @VisibleForTesting
  RPC.Server getClientRpcServer() {
    return clientRpcServer;
  }

  /** Allow access to the service RPC server for testing */
  @VisibleForTesting
  RPC.Server getServiceRpcServer() {
    return serviceRpcServer;
  }
  
  /**
   * Start client and service RPC servers.
   */
  void start() {
    clientRpcServer.start();
    if (serviceRpcServer != null) {
      serviceRpcServer.start();
    }
  }
  
  /**
   * Wait until the RPC servers have shutdown.
   */
  void join() throws InterruptedException {
    clientRpcServer.join();
    if (serviceRpcServer != null) {
      serviceRpcServer.join();
    }
  }

  /**
   * Stop client and service RPC servers.
   */
  void stop() {
    if (clientRpcServer != null) {
      clientRpcServer.stop();
    }
    if (serviceRpcServer != null) {
      serviceRpcServer.stop();
    }
  }
  
  InetSocketAddress getServiceRpcAddress() {
    return serviceRPCAddress;
  }

  InetSocketAddress getRpcAddress() {
    return clientRpcAddress;
  }

  private static UserGroupInformation getRemoteUser() throws IOException {
    return ServerlessNameNode.getRemoteUser();
  }
  
  
  /////////////////////////////////////////////////////
  // NamenodeProtocol
  /////////////////////////////////////////////////////
  @Override // NamenodeProtocol
  public BlocksWithLocations getBlocks(DatanodeInfo datanode, long size)
      throws IOException {
    if (size <= 0) {
      throw new IllegalArgumentException(
          "Unexpected not positive size: " + size);
    }
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    return namesystem.getBlockManager().getBlocks(datanode, size);
  }

  @Override // NamenodeProtocol
  public ExportedBlockKeys getBlockKeys() throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    return namesystem.getBlockManager().getBlockKeys();
  }

  @Override // ClientProtocol
  public Token<DelegationTokenIdentifier> getDelegationToken(Text renewer)
      throws IOException {
    checkNNStartup();
    return namesystem.getDelegationToken(renewer);
  }

  @Override // ClientProtocol
  public long renewDelegationToken(Token<DelegationTokenIdentifier> token)
      throws InvalidToken, IOException {
    checkNNStartup();
    return namesystem.renewDelegationToken(token);
  }

  @Override // ClientProtocol
  public void cancelDelegationToken(Token<DelegationTokenIdentifier> token)
      throws IOException {
    checkNNStartup();
    namesystem.cancelDelegationToken(token);
  }

  private ArrayList<User> userCache;
  /**
   * Used to test the performance of HopsFS and NDB for our latency benchmark.
   * @param connectionUrl The URL to be used to connect to the MySQL database.
   * @param dataSource Indicates whether to retrieve that data from NDB regardless of whether
   *                   or not it is in the local cache, or if we can use the local cache.
   * @param query The query to be executed.
   * @param id The ID of the desired user.
   */
  @Override
  public JsonObject latencyBenchmark(String connectionUrl, String dataSource, String query, int id) throws SQLException {
    JsonObject response = new JsonObject();

    System.out.println("Performing latency benchmark!");
    System.out.println("connectionUrl = " + connectionUrl);
    System.out.println("dataSource = " + dataSource);
    System.out.println("query = " + query);
    System.out.println("id = " + id);

    LOG.info("Performing latency benchmark!");
    LOG.debug("connectionUrl = " + connectionUrl);
    LOG.debug("dataSource = \"" + dataSource + "\"");
    LOG.debug("query = " + query);
    LOG.debug("id = " + id);

    if (userCache == null) {
      userCache = new ArrayList<>();
    }

    if (id >= 0 && dataSource != null && !dataSource.equals("FROM_NDB")) {
      System.out.println("Checking cache for user with ID " + id + " before executing query...");
      LOG.debug("Checking cache for user with ID " + id + " before executing query...");
      for (User user : userCache) {
        if (user.getId() == id) {
          System.out.println("Found user: ");
          LOG.debug("Found user: ");
          System.out.println(user.toString());
          LOG.debug(user.toString());
          JsonArray resultArr = new JsonArray();
          resultArr.add(packageUserAsJson(user));
          response.add("RESULT", resultArr);
          response.addProperty("RETRIEVED-FROM", "LOCAL CACHE");
          response.addProperty("WARM", "N/A");
          return response;
        }
      }
    }

    ArrayList<User> results = executeQuery(connectionUrl, query);

    JsonArray resultsArrayJson = new JsonArray();
    for (User user : results) {
      resultsArrayJson.add(packageUserAsJson(user));
    }

    response.add("RESULT", resultsArrayJson);
    response.addProperty("RETRIEVED-FROM", "NDB");

    response.addProperty("WARM", "N/A");

    System.out.println("Returning the following object from RPC Server latency benchmark: " + response.toString());
    LOG.debug("Returning the following object from RPC Server latency benchmark: " + response.toString());
    return response;
  }

  private ArrayList<User> executeQuery(String connectionUrl, String query) throws SQLException {
    ResultSet resultSet = null;

    Properties info = new Properties();
    info.put("user", "user");
    info.put("password", "password");
    Connection connection = DriverManager.getConnection(connectionUrl, info);

    if (connection == null) {
      System.out.println("ERROR: Failed to establish connection to MySQL database.");
      LOG.error("Failed to establish connection to MySQL database.");
      System.exit(1);
    }

    ArrayList<User> resultsList = new ArrayList<User>();

    try {
      Statement statement = connection.createStatement();
      System.out.println("Successfully connected.");
      System.out.println("Executing query now...");

      LOG.debug("Successfully connected.");
      LOG.debug("Executing query now...");

      resultSet = statement.executeQuery(query);

      System.out.println("Executed query. Displaying results now...");
      LOG.debug("Executed query. Displaying results now...");

      // Print results from select statement
      while (resultSet.next()) {
        // Apparently columns are 1-indexed and not 0-indexed because it's cool to
        // not follow established conventions.
        String result = String.format("%d - %s %s - %s, Department of %s",
                resultSet.getInt(1), resultSet.getString(2), resultSet.getString(3),
                resultSet.getString(4), resultSet.getString(5));

        User user = new UserEntry(resultSet.getInt(1), resultSet.getString(2), resultSet.getString(3),
                resultSet.getString(4), resultSet.getString(5));

        System.out.println("Result from query = " + result);
        LOG.debug("Result from query = " + result);
        resultsList.add(user);

        if (!userCache.contains(user)) {
          System.out.println("Adding user " + user.getId() + " - " + user.getFirstName() + " " + user.getLastName() + " to local cache.");
          System.out.println("Adding user " + user.getId() + " - " + user.getFirstName() + " " + user.getLastName() + " to local cache.");
          userCache.add(user);
          LOG.debug("Cache size after adding user " + user.getId() + ": " + userCache.size());
          LOG.debug("Cache size after adding user " + user.getId() + ": " + userCache.size());
        }
      }
    } catch (SQLException e) {
      e.printStackTrace();
    } finally {
      if (connection != null)
        connection.close();
    }

    return resultsList;
  }

  public static class UserEntry implements User {
    public int id;
    public String firstName;
    public String lastName;
    public String position;
    public String department;

    public UserEntry(int id, String firstName, String lastName, String position, String department) {
      this.id = id;
      this.firstName = firstName;
      this.lastName = lastName;
      this.position = position;
      this.department = department;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null)
        return false;

      if (!(obj instanceof User))
        return false;

      User other = (User)obj;

      return this.id == other.getId() && this.firstName.equals(other.getFirstName()) &&
              this.getLastName().equals(other.getLastName()) &&
              this.getPosition().equals(other.getPosition()) &&
              this.getDepartment().equals(other.getDepartment());
    }

    @Override
    public int getId() {
      return id;
    }

    @Override
    public void setId(int id) {
      this.id = id;
    }

    @Override
    public String getFirstName() {
      return firstName;
    }

    @Override
    public void setFirstName(String firstName) {
      this.firstName = firstName;
    }

    @Override
    public String getLastName() {
      return lastName;
    }

    @Override
    public void setLastName(String lastName) {
      this.lastName = lastName;
    }

    @Override
    public String getPosition() {
      return position;
    }

    @Override
    public void setPosition(String position) {
      this.position = position;
    }

    @Override
    public String getDepartment() {
      return department;
    }

    @Override
    public void setDepartment(String department) {
      this.department = department;
    }

    @Override
    public String toString() {
      return "User<<ID: " + id + ", First Name: " + firstName + ", Last Name: " + lastName + ", Position: " + position + ", Department: " + department + ">>";
    }
  }

  /**
   * Convert the given user to JSON.
   */
  private JsonObject packageUserAsJson(User user) {
    JsonObject jsonObj = new JsonObject();

    jsonObj.addProperty("ID", user.getId());
    jsonObj.addProperty("FIRST_NAME", user.getFirstName());
    jsonObj.addProperty("LAST_NAME", user.getLastName());
    jsonObj.addProperty("POSITION", user.getPosition());
    jsonObj.addProperty("DEPARTMENT", user.getDepartment());

    return jsonObj;
  }

  @Override // ClientProtocol
  public LocatedBlocks getBlockLocations(String src, long offset, long length)
      throws IOException {
    checkNNStartup();
    metrics.incrGetBlockLocations();
    return namesystem
        .getBlockLocations(getClientMachine(), src, offset, length);
  }
  
  @Override // ClientProtocol
  public FsServerDefaults getServerDefaults() throws IOException {
    checkNNStartup();
    return namesystem.getServerDefaults();
  }

  @Override // ClientProtocol
  public HdfsFileStatus create(String src, FsPermission masked,
      String clientName, EnumSetWritable<CreateFlag> flag, boolean createParent,
      short replication, long blockSize,
          CryptoProtocolVersion[] supportedVersions) throws IOException {
    checkNNStartup();
    String clientMachine = getClientMachine();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug(
          "*DIR* NameNode.create: file " + src + " for " + clientName + " at " +
              clientMachine);
    }
    if (!checkPathLength(src)) {
      throw new IOException(
          "create: Pathname too long.  Limit " + MAX_PATH_LENGTH +
              " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    HdfsFileStatus stat = namesystem.startFile(src, new PermissionStatus(
            getRemoteUser().getShortUserName(), null,
            masked), clientName, clientMachine, flag.get(), createParent,
        replication, blockSize, supportedVersions);
    metrics.incrFilesCreated();
    metrics.incrCreateFileOps();
    return stat;
  }

  @Override // ClientProtocol
  public LastBlockWithStatus append(String src, String clientName,
      EnumSetWritable<CreateFlag> flag) throws IOException {
    checkNNStartup();
    String clientMachine = getClientMachine();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.append: file "
          +src+" for "+clientName+" at "+clientMachine);
    }
    LastBlockWithStatus info = namesystem.appendFile(src, clientName, clientMachine, flag.get());
    metrics.incrFilesAppended();
    return info;
  }

  @Override // ClientProtocol
  public boolean recoverLease(String src, String clientName)
      throws IOException {
    checkNNStartup();
    String clientMachine = getClientMachine();
    return namesystem.recoverLease(src, clientName, clientMachine);
  }

  @Override // ClientProtocol
  public boolean setReplication(String src, short replication)
      throws IOException {
    checkNNStartup();
    return namesystem.setReplication(src, replication);
  }

  @Override
  public void setStoragePolicy(String src, String policyName)
      throws IOException {
    checkNNStartup();
    namesystem.setStoragePolicy(src, policyName);
  }

  @Override
  public BlockStoragePolicy getStoragePolicy(byte storagePolicyID) throws IOException {
    return namesystem.getStoragePolicy(storagePolicyID);
  }
  
  @Override
  public BlockStoragePolicy[] getStoragePolicies() throws IOException {
    checkNNStartup();
    return namesystem.getStoragePolicies();
  }

  @Override // ClientProtocol
  public void setMetaStatus(String src, MetaStatus metaStatus)
      throws AccessControlException, FileNotFoundException, SafeModeException,
      UnresolvedLinkException, IOException {
    namesystem.setMetaStatus(src, metaStatus);
  }

  @Override // ClientProtocol
  public void setPermission(String src, FsPermission permissions)
      throws IOException {
    checkNNStartup();
    namesystem.setPermission(src, permissions);
  }

  @Override // ClientProtocol
  public void setOwner(String src, String username, String groupname)
      throws IOException {
    checkNNStartup();
    namesystem.setOwner(src, username, groupname);
  }

  @Override // ClientProtocol
  public LocatedBlock addBlock(String src, String clientName,
      ExtendedBlock previous, DatanodeInfo[] excludedNodes, long fileId, String[] favoredNodes) throws IOException {
  checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug(
          "*BLOCK* NameNode.addBlock: file " + src + " fileId=" + fileId + " for " + clientName);
    }
    HashSet<Node> excludedNodesSet = null;

    if (excludedNodes != null) {
      excludedNodesSet = new HashSet<>(excludedNodes.length);
      for (Node node : excludedNodes) {
        excludedNodesSet.add(node);
      }
    }
    List<String> favoredNodesList = (favoredNodes == null) ? null
        : Arrays.asList(favoredNodes);
    LocatedBlock locatedBlock = namesystem
        .getAdditionalBlock(src, fileId, clientName, previous, excludedNodesSet, favoredNodesList);
    if (locatedBlock != null) {
      metrics.incrAddBlockOps();
    }
    return locatedBlock;
  }

  @Override // ClientProtocol
  public LocatedBlock getAdditionalDatanode(final String src,
      final long fileId, final ExtendedBlock blk,
      final DatanodeInfo[] existings,
      final String[] existingStorageIDs,
      final DatanodeInfo[] excludes,
      final int numAdditionalNodes,
      final String clientName) throws IOException {
    checkNNStartup();
    if (LOG.isDebugEnabled()) {
      LOG.debug("getAdditionalDatanode: src=" + src
          + ", fileId=" + fileId
          + ", blk=" + blk
          + ", existings=" + Arrays.asList(existings)
          + ", excludes=" + Arrays.asList(excludes)
          + ", numAdditionalNodes=" + numAdditionalNodes
          + ", clientName=" + clientName);
    }

    metrics.incrGetAdditionalDatanodeOps();

    HashSet<Node> excludeSet = null;
    if (excludes != null) {
      excludeSet = new HashSet<>(excludes.length);
      for (Node node : excludes) {
        excludeSet.add(node);
      }
    }
    return namesystem.getAdditionalDatanode(src, fileId, blk, existings,
        existingStorageIDs, excludeSet, numAdditionalNodes, clientName);
  }

  /**
   * The client needs to give up on the block.
   */
  @Override // ClientProtocol
  public void abandonBlock(ExtendedBlock b, long fileId, String src, String holder)
      throws IOException {
    checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog
          .debug("*BLOCK* NameNode.abandonBlock: " + b + " of file " + src);
    }
    if (!namesystem.abandonBlock(b, fileId, src, holder)) {
      throw new IOException("Cannot abandon block during write to " + src);
    }
  }

  @Override // ClientProtocol
  public boolean complete(String src, String clientName, ExtendedBlock last,  long fileId, final byte[] data)
      throws IOException {
    checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog
          .debug("*DIR* NameNode.complete: " + src + " fileId=" + fileId +" for " + clientName);
    }

    return namesystem.completeFile(src, clientName, last, fileId, data);
  }

  /**
   * The client has detected an error on the specified located blocks
   * and is reporting them to the server.  For now, the namenode will
   * mark the block as corrupt.  In the future we might
   * check the blocks are actually corrupt.
   */
  @Override // ClientProtocol, DatanodeProtocol
  public void reportBadBlocks(LocatedBlock[] blocks) throws IOException {
    checkNNStartup();
    namesystem.reportBadBlocks(blocks);
  }

  @Override // ClientProtocol
  public LocatedBlock updateBlockForPipeline(ExtendedBlock block,
      String clientName) throws IOException {
    checkNNStartup();
    return namesystem.updateBlockForPipeline(block, clientName);
  }


  @Override // ClientProtocol
  public void updatePipeline(String clientName, ExtendedBlock oldBlock,
      ExtendedBlock newBlock, DatanodeID[] newNodes, String[] newStorageIDs)
      throws IOException {
    checkNNStartup();
    namesystem.updatePipeline(clientName, oldBlock, newBlock, newNodes, newStorageIDs);
  }
  
  @Override // DatanodeProtocol
  public void commitBlockSynchronization(ExtendedBlock block,
      long newgenerationstamp, long newlength, boolean closeFile,
      boolean deleteblock, DatanodeID[] newtargets, String[] newtargetstorages)
      throws IOException {
    checkNNStartup();
    namesystem.commitBlockSynchronization(block, newgenerationstamp, newlength,
        closeFile, deleteblock, newtargets, newtargetstorages);
  }
  
  @Override // ClientProtocol
  public long getPreferredBlockSize(String filename) throws IOException {
    checkNNStartup();
    return namesystem.getPreferredBlockSize(filename);
  }

  @Deprecated
  @Override // ClientProtocol
  public boolean rename(String src, String dst) throws IOException {
    checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.rename: " + src + " to " + dst);
    }
    if (!checkPathLength(dst)) {
      throw new IOException(
          "rename: Pathname too long.  Limit " + MAX_PATH_LENGTH +
              " characters, " + MAX_PATH_DEPTH + " levels.");
    }

    RetryCacheEntry cacheEntry = LightWeightCacheDistributed.getTransactional();
    if (cacheEntry != null && cacheEntry.isSuccess()) {
      return true; // Return previous response
    }
    
    boolean ret = false;
    try{
      ret = namesystem.renameTo(src, dst);
    } finally {
      LightWeightCacheDistributed.putTransactional(ret);
    }
    if (ret) {
      metrics.incrFilesRenamed();
    }
    return ret;
  }
  
  @Override // ClientProtocol
  public void concat(String trg, String[] src) throws IOException {
    checkNNStartup();
    namesystem.concat(trg, src);
  }
  
  @Override // ClientProtocol
  public void rename2(String src, String dst, Options.Rename... options)
      throws IOException {
    checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.rename: " + src + " to " + dst);
    }
    if (!checkPathLength(dst)) {
      throw new IOException(
          "rename: Pathname too long.  Limit " + MAX_PATH_LENGTH +
              " characters, " + MAX_PATH_DEPTH + " levels.");
    }

    RetryCacheEntry cacheEntry = LightWeightCacheDistributed.getTransactional();
    if (cacheEntry != null && cacheEntry.isSuccess()) {
      return; // Return previous response
    }

    boolean success = false;
    try {
      namesystem.renameTo(src, dst, options);
      success = true;
    } finally {
      LightWeightCacheDistributed.putTransactional(success);
    }
    metrics.incrFilesRenamed();
  }

  @Override // ClientProtocol
  public boolean truncate(String src, long newLength, String clientName)
      throws IOException {
    if(stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.truncate: " + src + " to " +
          newLength);
    }
    String clientMachine = getClientMachine();
    try {
      return namesystem.truncate(
          src, newLength, clientName, clientMachine, now());
    } finally {
      metrics.incrFilesTruncated();
    }
  }

  @Override // ClientProtocol
  public boolean delete(String src, boolean recursive) throws IOException {
    checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug(
          "*DIR* Namenode.delete: src=" + src + ", recursive=" + recursive);
    }

    boolean ret;
    ret = namesystem.delete(src, recursive);

    if (ret) {
      metrics.incrDeleteFileOps();
    }
    return ret;
  }

  /**
   * Check path length does not exceed maximum.  Returns true if
   * length and depth are okay.  Returns false if length is too long
   * or depth is too great.
   */
  private boolean checkPathLength(String src) {
    Path srcPath = new Path(src);
    return (src.length() <= MAX_PATH_LENGTH &&
        srcPath.depth() <= MAX_PATH_DEPTH);
  }

  @Override // ClientProtocol
  public boolean mkdirs(String src, FsPermission masked, boolean createParent)
      throws IOException {
    checkNNStartup();
    if (stateChangeLog.isDebugEnabled()) {
      stateChangeLog.debug("*DIR* NameNode.mkdirs: " + src);
    }
    if (!checkPathLength(src)) {
      throw new IOException(
          "mkdirs: Pathname too long.  Limit " + MAX_PATH_LENGTH +
              " characters, " + MAX_PATH_DEPTH + " levels.");
    }
    return namesystem.mkdirs(src, new PermissionStatus(
            getRemoteUser().getShortUserName(), null,
            masked), createParent);
  }

  @Override // ClientProtocol
  public void renewLease(String clientName) throws IOException {
    checkNNStartup();
    namesystem.renewLease(clientName);
  }

  @Override // ClientProtocol
  public DirectoryListing getListing(String src, byte[] startAfter,
      boolean needLocation) throws IOException {
    checkNNStartup();
    DirectoryListing files =
        namesystem.getListing(src, startAfter, needLocation);
    if (files != null) {
      metrics.incrGetListingOps();
      metrics.incrFilesInGetListingOps(files.getPartialListing().length);
    }
    return files;
  }

  @Override // ClientProtocol
  public HdfsFileStatus getFileInfo(String src) throws IOException {
    checkNNStartup();
    metrics.incrFileInfoOps();
    return namesystem.getFileInfo(src, true);
  }
  
  @Override // ClientProtocol
  public boolean isFileClosed(String src) throws IOException{
    checkNNStartup();
    return namesystem.isFileClosed(src);
  }

  @Override // ClientProtocol
  public HdfsFileStatus getFileLinkInfo(String src) throws IOException {
    checkNNStartup();
    metrics.incrFileInfoOps();
    return namesystem.getFileInfo(src, false);
  }
  
  @Override // ClientProtocol
  public long[] getStats() throws IOException {
    checkNNStartup();
    return namesystem.getStats();
  }

  @Override // ClientProtocol
  public DatanodeInfo[] getDatanodeReport(DatanodeReportType type)
      throws IOException {
    checkNNStartup();
    DatanodeInfo results[] = namesystem.datanodeReport(type);
    return results;
  }

  @Override // ClientProtocol
  public DatanodeStorageReport[] getDatanodeStorageReport(
      DatanodeReportType type) throws IOException {
    checkNNStartup();
    final DatanodeStorageReport[] reports = namesystem.getDatanodeStorageReport(type);
    return reports;
  }

  @Override // ClientProtocol
  public boolean setSafeMode(SafeModeAction action, boolean isChecked)
      throws IOException {
    checkNNStartup();
    return namesystem.setSafeMode(action);
  }

  @Override // ClientProtocol
  public void refreshNodes() throws IOException {
    checkNNStartup();
    namesystem.refreshNodes();
  }

  @Override // ClientProtocol
  public RollingUpgradeInfo rollingUpgrade(RollingUpgradeAction action) throws IOException {
    checkNNStartup();
    LOG.info("rollingUpgrade " + action);
    switch(action) {
    case QUERY:
      return namesystem.queryRollingUpgrade();
    case PREPARE:
      return namesystem.startRollingUpgrade();
    case FINALIZE:
      return namesystem.finalizeRollingUpgrade();
    default:
      throw new UnsupportedActionException(action + " is not yet supported.");
    }
  }
  
  @Override // ClientProtocol
  public CorruptFileBlocks listCorruptFileBlocks(String path, String cookie)
      throws IOException {
    checkNNStartup();
    String[] cookieTab = new String[]{cookie};
    Collection<FSNamesystem.CorruptFileBlockInfo> fbs =
        namesystem.listCorruptFileBlocks(path, cookieTab);

    String[] files = new String[fbs.size()];
    int i = 0;
    for (FSNamesystem.CorruptFileBlockInfo fb : fbs) {
      files[i++] = fb.path;
    }
    return new CorruptFileBlocks(files, cookieTab[0]);
  }

  /**
   * Tell all datanodes to use a new, non-persistent bandwidth value for
   * dfs.datanode.balance.bandwidthPerSec.
   *
   * @param bandwidth
   *     Balancer bandwidth in bytes per second for all datanodes.
   * @throws IOException
   */
  @Override // ClientProtocol
  public void setBalancerBandwidth(long bandwidth) throws IOException {
    checkNNStartup();
    namesystem.setBalancerBandwidth(bandwidth);
  }
  
  @Override // ClientProtocol
  public ContentSummary getContentSummary(String path) throws IOException {
    checkNNStartup();
    return namesystem.getContentSummary(path);
  }

  @Override // ClientProtocol
  public void setQuota(String path, long namespaceQuota, long storagespaceQuota,
                       StorageType type)
      throws IOException {
    checkNNStartup();
    namesystem.setQuota(path, namespaceQuota, storagespaceQuota, type);
  }
  
  @Override // ClientProtocol
  public void fsync(String src, long fileId, String clientName, long lastBlockLength)
      throws IOException {
    checkNNStartup();
    namesystem.fsync(src, fileId, clientName, lastBlockLength);
  }

  @Override // ClientProtocol
  public void setTimes(String src, long mtime, long atime) throws IOException {
    checkNNStartup();
    namesystem.setTimes(src, mtime, atime);
  }

  @Override // ClientProtocol
  public void createSymlink(String target, String link, FsPermission dirPerms,
      boolean createParent) throws IOException {
    checkNNStartup();
    /* We enforce the MAX_PATH_LENGTH limit even though a symlink target
     * URI may refer to a non-HDFS file system. 
     */
    if (!checkPathLength(link)) {
      throw new IOException("Symlink path exceeds " + MAX_PATH_LENGTH +
          " character limit");

    }

    final UserGroupInformation ugi = getRemoteUser();
    namesystem.createSymlink(target, link,
        new PermissionStatus(ugi.getShortUserName(), null, dirPerms),
        createParent);
  }

  @Override // ClientProtocol
  public String getLinkTarget(String path) throws IOException {
    checkNNStartup();
    metrics.incrGetLinkTargetOps();
    HdfsFileStatus stat = null;
    try {
      stat = namesystem.getFileInfo(path, false);
    } catch (UnresolvedPathException e) {
      return e.getResolvedPath().toString();
    } catch (UnresolvedLinkException e) {
      // The NameNode should only throw an UnresolvedPathException
      throw new AssertionError("UnresolvedLinkException thrown");
    }
    if (stat == null) {
      throw new FileNotFoundException("File does not exist: " + path);
    } else if (!stat.isSymlink()) {
      throw new IOException("Path " + path + " is not a symbolic link");
    }
    return stat.getSymlink().toString();
  }


  @Override // DatanodeProtocol
  public DatanodeRegistration registerDatanode(DatanodeRegistration nodeReg)
      throws IOException {
    checkNNStartup();
    verifySoftwareVersion(nodeReg);
    namesystem.registerDatanode(nodeReg);
    return nodeReg;
  }

  @Override // DatanodeProtocol
  public HeartbeatResponse sendHeartbeat(DatanodeRegistration nodeReg,
      StorageReport[] reports, long dnCacheCapacity, long dnCacheUsed, int xmitsInProgress, int xceiverCount,
      int failedVolumes, VolumeFailureSummary volumeFailureSummary) throws IOException {
    checkNNStartup();
    verifyRequest(nodeReg);

    return namesystem.handleHeartbeat(nodeReg, reports, dnCacheCapacity, dnCacheUsed, xceiverCount, xmitsInProgress,
        failedVolumes, volumeFailureSummary);
  }

  @Override // DatanodeProtocol
  public DatanodeCommand blockReport(DatanodeRegistration nodeReg,
        String poolId, StorageBlockReport[] reports,
        BlockReportContext context) throws IOException {
    checkNNStartup();
    verifyRequest(nodeReg);
    if (blockStateChangeLog.isDebugEnabled()) {
      blockStateChangeLog.debug(
          "*BLOCK* NameNode.blockReport: from " + nodeReg +
              ", reports.length=" + reports.length);
    }

    final BlockManager bm = namesystem.getBlockManager();
    boolean noStaleStorages = false;
    for (int r = 0; r < reports.length; r++) {
      final BlockReport blocks = reports[r].getReport();
      //
      // BlockManager.processReport accumulates information of prior calls
      // for the same node and storage, so the value returned by the last
      // call of this loop is the final updated value for noStaleStorage.
      //
      noStaleStorages = bm.processReport(nodeReg, reports[r].getStorage(),
          blocks, context, (r == reports.length - 1));
      metrics.incrStorageBlockReportOps();
    }

    if(noStaleStorages &&
        !namesystem.isRollingUpgradeTX()) {
      return new FinalizeCommand(poolId);
    } else {
      return null;
    }
  }

  @Override // DatanodeProtocol
  public DatanodeCommand reportHashes(DatanodeRegistration nodeReg,
                                     String poolId, StorageBlockReport[] reports) throws IOException {
    checkNNStartup();
    verifyRequest(nodeReg);
    if (blockStateChangeLog.isDebugEnabled()) {
      blockStateChangeLog.debug("*BLOCK* NameNode.reportHashes: from " + nodeReg +
                      ", reports.length=" + reports.length);
    }

    HashesMismatchCommand hmc = new HashesMismatchCommand();
    final BlockManager bm = namesystem.getBlockManager();
    for(StorageBlockReport r : reports) {
      final BlockReport blocks = r.getReport();
      List<Integer> mb = bm.checkHashes(nodeReg, r.getStorage(), blocks);
      hmc.addStorageBuckets(r.getStorage().getStorageID(), mb);
    }

    return hmc;
  }

  @Override
  public DatanodeCommand cacheReport(DatanodeRegistration nodeReg,
      String poolId, List<Long> blockIds, long cacheCapacity, long cacheUsed) throws IOException {
    checkNNStartup();
    verifyRequest(nodeReg);
    if (blockStateChangeLog.isDebugEnabled()) {
      blockStateChangeLog.debug("*BLOCK* NameNode.cacheReport: "
           + "from " + nodeReg + " " + blockIds.size() + " blocks");
    }
    namesystem.getCacheManager().processCacheReport(nodeReg, blockIds, cacheCapacity, cacheUsed);
    return null;
  }

  @Override // DatanodeProtocol
  public void blockReceivedAndDeleted(DatanodeRegistration nodeReg,
      String poolId, StorageReceivedDeletedBlocks[] receivedAndDeletedBlocks)
      throws IOException {
    checkNNStartup();
    verifyRequest(nodeReg);
    metrics.incrBlockReceivedAndDeletedOps();
    if (blockStateChangeLog.isDebugEnabled()) {
      blockStateChangeLog.debug(
          "*BLOCK* NameNode.blockReceivedAndDeleted: " + "from " + nodeReg +
              " " + receivedAndDeletedBlocks.length + " blocks.");
    }

    for(StorageReceivedDeletedBlocks r : receivedAndDeletedBlocks) {
      namesystem.processIncrementalBlockReport(nodeReg, r);
    }
  }
  
  @Override // DatanodeProtocol
  public void errorReport(DatanodeRegistration nodeReg, int errorCode,
      String msg) throws IOException {
    checkNNStartup();
    String dnName = (nodeReg == null) ? "Unknown DataNode" : nodeReg.toString();

    if (errorCode == DatanodeProtocol.NOTIFY) {
      LOG.info("Error report from " + dnName + ": " + msg);
      return;
    }
    verifyRequest(nodeReg);

    if (errorCode == DatanodeProtocol.DISK_ERROR) {
      LOG.warn("Disk error on " + dnName + ": " + msg);
    } else if (errorCode == DatanodeProtocol.FATAL_DISK_ERROR) {
      LOG.warn("Fatal disk error on " + dnName + ": " + msg);
      namesystem.getBlockManager().getDatanodeManager().removeDatanode(nodeReg, false);
    } else {
      LOG.info("Error report from " + dnName + ": " + msg);
    }
  }

  @Override // DatanodeProtocol, NamenodeProtocol
  public NamespaceInfo versionRequest() throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    return namesystem.getNamespaceInfo();
  }

  @Override
  public byte[] getSmallFileData(int id) throws IOException {
    return namesystem.getSmallFileData(id);
  }

  /**
   * Verifies the given registration.
   *
   * @param nodeReg
   *     node registration
   * @throws UnregisteredNodeException
   *     if the registration is invalid
   */
  private void verifyRequest(NodeRegistration nodeReg) throws IOException {
    // verify registration ID
    final String id = nodeReg.getRegistrationID();
    final String expectedID = namesystem.getRegistrationID();
    if (!expectedID.equals(id)) {
      LOG.warn("Registration IDs mismatched: the "
          + nodeReg.getClass().getSimpleName() + " ID is " + id
          + " but the expected ID is " + expectedID);
      throw new UnregisteredNodeException(nodeReg);
    }    
  }


  @Override // RefreshAuthorizationPolicyProtocol
  public void refreshServiceAcl() throws IOException {
    checkNNStartup();
    if (!serviceAuthEnabled) {
      throw new AuthorizationException(
          "Service Level Authorization not enabled!");
    }

    this.clientRpcServer
        .refreshServiceAcl(new Configuration(), new HDFSPolicyProvider());
    if (this.serviceRpcServer != null) {
      this.serviceRpcServer
          .refreshServiceAcl(new Configuration(), new HDFSPolicyProvider());
    }
  }

  @Override // RefreshAuthorizationPolicyProtocol
  public void refreshUserToGroupsMappings() throws IOException {
    LOG.info("Refreshing all user-to-groups mappings. Requested by user: " +
        getRemoteUser().getShortUserName());
    Groups.getUserToGroupsMappingService().refresh();
  }

  @Override // RefreshAuthorizationPolicyProtocol
  public void refreshSuperUserGroupsConfiguration() {
    LOG.info("Refreshing SuperUser proxy group mapping list ");

    ProxyUsers.refreshSuperUserGroupsConfiguration();
  }

  @Override // RefreshCallQueueProtocol
  public void refreshCallQueue() {
    LOG.info("Refreshing call queue.");

    Configuration conf = new Configuration();
    clientRpcServer.refreshCallQueue(conf);
    if (this.serviceRpcServer != null) {
      serviceRpcServer.refreshCallQueue(conf);
    }
  }

  @Override // GenericRefreshProtocol
  public Collection<RefreshResponse> refresh(String identifier, String[] args) {
    // Let the registry handle as needed
    return RefreshRegistry.defaultRegistry().dispatch(identifier, args);
  }
  
  @Override // GetUserMappingsProtocol
  public String[] getGroupsForUser(String user) throws IOException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Getting groups for user " + user);
    }
    return UserGroupInformation.createRemoteUser(user).getGroupNames();
  }

  /**
   * Verify version.
   *
   * @param version
   * @throws IOException
   */
  void verifyLayoutVersion(int version) throws IOException {
    if (version != HdfsConstants.NAMENODE_LAYOUT_VERSION) {
      throw new IncorrectVersionException(
          HdfsConstants.NAMENODE_LAYOUT_VERSION, version, "data node");
    }
  }
  
  private void verifySoftwareVersion(DatanodeRegistration dnReg)
      throws IncorrectVersionException, IOException {
    String dnVersion = dnReg.getSoftwareVersion();
    if (VersionUtil.compareVersions(dnVersion, minimumDataNodeVersion) < 0) {
      IncorrectVersionException ive =
          new IncorrectVersionException(minimumDataNodeVersion, dnVersion,
              "DataNode", "NameNode");
      LOG.warn(ive.getMessage() + " DN: " + dnReg);
      throw ive;
    }
    String nnVersion = VersionInfo.getVersion();
    if (!dnVersion.equals(nnVersion)) {
      String messagePrefix = "Reported DataNode version '" + dnVersion +
          "' of DN " + dnReg + " does not match NameNode version '" +
          nnVersion + "'";
      long nnCTime = StorageInfo.getStorageInfoFromDB().getCTime();
      long dnCTime = dnReg.getStorageInfo().getCTime();
      if (nnCTime != dnCTime) {
        IncorrectVersionException ive = new IncorrectVersionException(
            messagePrefix + " and CTime of DN ('" + dnCTime +
            "') does not match CTime of NN ('" + nnCTime + "')");
        LOG.warn(ive.toString(), ive);
        throw ive;
      } else {
        LOG.info(
            messagePrefix + ". Note: This is normal during a rolling upgrade.");
      }
    }
  }

  public static String getClientMachine() {
    String clientMachine = NamenodeWebHdfsMethods.getRemoteAddress();
    if (clientMachine == null) { //not a web client
      clientMachine = Server.getRemoteAddress();
    }
    if (clientMachine == null) { //not a RPC client
      clientMachine = "";
    }
    return clientMachine;
  }

  @Override
  public DataEncryptionKey getDataEncryptionKey() throws IOException {
    checkNNStartup();
    return namesystem.getBlockManager().generateDataEncryptionKey();
  }


  @Override
  public SortedActiveNodeList getActiveNamenodes() throws IOException {
    return nn.getActiveNameNodes();
  }

  @Override
  public ActiveNode getNextNamenodeToSendBlockReport(long noOfBlks, DatanodeRegistration nodeReg) throws IOException {
    verifyRequest(nodeReg);
    return nn.getNextNamenodeToSendBlockReport(noOfBlks, nodeReg);
  }

  @Override
  public void blockReportCompleted(DatanodeRegistration nodeReg, DatanodeStorage[] storages, boolean success) throws
      IOException {
    namesystem.getBlockManager().blockReportCompleted(nodeReg, storages, success);
  }

  @Override
  public void ping() throws IOException {
  }

  @Override
  public void ping(int targetDeployment) throws IOException {
    throw new NotImplementedException("The NameNode RPC Server does not support the targeted ping operation.");
  }

  @Override
  public void prewarm(int numThreadsPerDeployment, int numPingsPerThread) throws IOException {
    throw new NotImplementedException("The NameNode RPC Server does not support the prewarm operation.");
  }

  @Override
  public SortedActiveNodeList getActiveNamenodesForClient() throws IOException {
    return nn.getActiveNameNodes();
  }

  @Override
  public void changeConf(List<String> props, List<String> newVals)
      throws IOException {
    namesystem.changeConf(props, newVals);
  }
  
  @Override // ClientProtocol
  public HdfsFileStatus create(String src, FsPermission masked,
      String clientName, EnumSetWritable<CreateFlag> flag, boolean createParent,
      short replication, long blockSize, CryptoProtocolVersion[] supportedVersions, EncodingPolicy policy)
      throws IOException {
    checkNNStartup();
    HdfsFileStatus stat =
        create(src, masked, clientName, flag, createParent, replication,
            blockSize, supportedVersions);
    if (policy != null) {
      if (!namesystem.isErasureCodingEnabled()) {
        throw new IOException("Requesting encoding although erasure coding" +
            " was disabled");
      }
      LOG.info("Create file " + src + " with policy " + policy.toString());
      namesystem.addEncodingStatus(src, policy,
          EncodingStatus.Status.ENCODING_REQUESTED, false);
    }
    return stat;
  }

  @Override // ClientProtocol
  public EncodingStatus getEncodingStatus(String filePath) throws IOException {
    checkNNStartup();
    EncodingStatus status = namesystem.getEncodingStatus(filePath);

    if (status.getStatus() == EncodingStatus.Status.DELETED) {
      throw new IOException("Trying to read encoding status of a deleted file");
    }

    return status;
  }

  @Override // ClientProtocol
  public void encodeFile(String filePath, EncodingPolicy policy)
      throws IOException {
    checkNNStartup();
    if (!namesystem.isErasureCodingEnabled()) {
      throw new IOException("Requesting encoding although erasure coding" +
          " was disabled");
    }
    namesystem.addEncodingStatus(filePath, policy,
        EncodingStatus.Status.COPY_ENCODING_REQUESTED, true);
  }

  @Override
  public void revokeEncoding(String filePath, short replication)
      throws IOException {
    checkNNStartup();
    if (!namesystem.isErasureCodingEnabled()) {
      throw new IOException("Requesting revoke although erasure coding" +
          " was disabled");
    }
    namesystem.revokeEncoding(filePath, replication);
  }

  @Override // ClientProtocol
  public LocatedBlocks getMissingBlockLocations(String filePath)
      throws IOException {
    checkNNStartup();
    return namesystem.getMissingBlockLocations(getClientMachine(), filePath);
  }

  @Override // ClientProtocol
  public void addBlockChecksum(String src, int blockIndex, long checksum)
      throws IOException {
    checkNNStartup();
    namesystem.addBlockChecksum(src, blockIndex, checksum);
  }

  @Override // ClientProtocol
  public long getBlockChecksum(String src, int blockIndex) throws IOException {
    checkNNStartup();
    return namesystem.getBlockChecksum(src, blockIndex);
  }

  @Override // ClientProtocol
  public LocatedBlock getRepairedBlockLocations(String sourcePath,
      String parityPath, LocatedBlock block, boolean isParity)
      throws IOException {
    checkNNStartup();
    return namesystem.getRepairedBlockLocations(getClientMachine(), sourcePath,
        parityPath, block, isParity);
  }

  private void checkNNStartup() throws IOException {
    if (!this.nn.isStarted()) {
      throw new IOException(this.nn.getRole() + " still not started");
    }
  }
  
  @Override // ClientProtocol
  public void checkAccess(String path, FsAction mode) throws IOException {
    checkNNStartup();
    namesystem.checkAccess(path, mode);
  }

  @Override // ClientProtocol
  public void modifyAclEntries(String src, List<AclEntry> aclSpec)
      throws IOException {
    checkNNStartup();
    namesystem.modifyAclEntries(src, aclSpec);
  }

  @Override // ClienProtocol
  public void removeAclEntries(String src, List<AclEntry> aclSpec)
      throws IOException {
    checkNNStartup();
    namesystem.removeAclEntries(src, aclSpec);
  }

  @Override // ClientProtocol
  public void removeDefaultAcl(String src) throws IOException {
    checkNNStartup();
    namesystem.removeDefaultAcl(src);
  }

  @Override // ClientProtocol
  public void removeAcl(String src) throws IOException {
    checkNNStartup();
    namesystem.removeAcl(src);
  }

  @Override // ClientProtocol
  public void setAcl(String src, List<AclEntry> aclSpec) throws IOException {
    checkNNStartup();
    namesystem.setAcl(src, aclSpec);
  }

  @Override // ClientProtocol
  public AclStatus getAclStatus(String src) throws IOException {
    checkNNStartup();
    return namesystem.getAclStatus(src);
  }

  @Override // ClientProtocol
  public LastUpdatedContentSummary getLastUpdatedContentSummary(String path)
      throws AccessControlException, FileNotFoundException,
      UnresolvedLinkException, IOException {
    checkNNStartup();
    return namesystem.getLastUpdatedContentSummary(path);
  }
  
  @Override
  public long addCacheDirective(
      CacheDirectiveInfo path, EnumSet<CacheFlag> flags) throws IOException {
    checkNNStartup();
    return namesystem.addCacheDirective(path, flags);
  }

  @Override
  public void modifyCacheDirective(
      CacheDirectiveInfo directive, EnumSet<CacheFlag> flags) throws IOException {
    checkNNStartup();
    namesystem.modifyCacheDirective(directive, flags);
  }

  @Override
  public void removeCacheDirective(long id) throws IOException {
    checkNNStartup();
    namesystem.removeCacheDirective(id);
  }

  @Override
  public BatchedEntries<CacheDirectiveEntry> listCacheDirectives(long prevId,
      CacheDirectiveInfo filter) throws IOException {
    checkNNStartup();
    if (filter == null) {
      filter = new CacheDirectiveInfo.Builder().build();
    }
    return namesystem.listCacheDirectives(prevId, filter);
  }

  @Override
  public void addCachePool(CachePoolInfo info) throws IOException {
    checkNNStartup();
    namesystem.addCachePool(info);
  }

  @Override
  public void modifyCachePool(CachePoolInfo info) throws IOException {
    checkNNStartup();
    namesystem.modifyCachePool(info);
  }

  @Override
  public void removeCachePool(String cachePoolName) throws IOException {
    checkNNStartup();
    namesystem.removeCachePool(cachePoolName);
  }

  @Override
  public BatchedEntries<CachePoolEntry> listCachePools(String prevKey)
      throws IOException {
    checkNNStartup();
    return namesystem.listCachePools(prevKey != null ? prevKey : "");
  }

  @Override
  public void addUser(String userName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.addUser(userName);
  }

  @Override
  public void addGroup(String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.addGroup(groupName);
  }

  @Override
  public void addUserToGroup(String userName, String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.addUserToGroup(userName,groupName);
  }

  @Override
  public void removeUser(String userName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.removeUser(userName);
  }

  @Override
  public void removeGroup(String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.removeGroup(groupName);
  }

  @Override
  public void removeUserFromGroup(String userName, String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.removeUserFromGroup(userName, groupName);
  }

  @Override
  public void invCachesUserRemoved(String userName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.invCacheUserRemoved(userName);
  }

  @Override
  public void invCachesGroupRemoved(String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.invCacheGroupRemoved(groupName);
  }

  @Override
  public void invCachesUserRemovedFromGroup(String userName, String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.invCacheUserRemovedFromGroup(userName, groupName);
  }

  @Override
  public void invCachesUserAddedToGroup(String userName, String groupName) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    UsersGroups.invCacheUserAddedToGroup(userName, groupName);
  }

  @Override
  public long getEpochMS() throws IOException {
    return System.currentTimeMillis();
  }

  @Override // TraceAdminProtocol
  public SpanReceiverInfo[] listSpanReceivers() throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    return nn.tracerConfigurationManager.listSpanReceivers();
  }

  @Override // TraceAdminProtocol
  public long addSpanReceiver(SpanReceiverInfo info) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    return nn.tracerConfigurationManager.addSpanReceiver(info);
  }

  @Override // TraceAdminProtocol
  public void removeSpanReceiver(long id) throws IOException {
    checkNNStartup();
    namesystem.checkSuperuserPrivilege();
    nn.tracerConfigurationManager.removeSpanReceiver(id);
  }
  
  @Override
  public void createEncryptionZone(String src, String keyName)
    throws IOException {
    namesystem.createEncryptionZone(src, keyName);
  }

  @Override
  public EncryptionZone getEZForPath(String src)
    throws IOException {
    return namesystem.getEZForPath(src);
  }

  @Override
  public BatchedEntries<EncryptionZone> listEncryptionZones(
      long prevId) throws IOException {
    return namesystem.listEncryptionZones(prevId);
  }

  @Override
  public void setXAttr(String src, XAttr xAttr, EnumSet<XAttrSetFlag> flag)
      throws IOException {
    namesystem.setXAttr(src, xAttr, flag);
  }
  
  @Override
  public List<XAttr> getXAttrs(String src, List<XAttr> xAttrs)
      throws IOException {
    return namesystem.getXAttrs(src, xAttrs);
  }

  @Override
  public List<XAttr> listXAttrs(String src) throws IOException {
    return namesystem.listXAttrs(src);
  }
  
  @Override
  public void removeXAttr(String src, XAttr xAttr) throws IOException {
    namesystem.removeXAttr(src, xAttr);
  }

  @VisibleForTesting
  void setFSNamesystem(FSNamesystem fsn){
    namesystem = fsn;
  }
}
